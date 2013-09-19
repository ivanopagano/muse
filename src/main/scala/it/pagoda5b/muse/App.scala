package it.pagoda5b.muse

import org.mashupbots.socko._
import org.mashupbots.socko.webserver._
import org.mashupbots.socko.routes._
import org.mashupbots.socko.handlers._
import org.mashupbots.socko.events._
import akka.actor._
import akka.routing.FromConfig
import Console._

/**
 * this is the main app
 */
object MuseServer extends App {

	/*
	 * this message is used to signal shutdown of 
	 * the app server and the actor system
	 */
	case class ShutDownSequence(reason: String)

  /*
   * this actor is in charge to stop the actor system
   */
  class ShutDownActor extends Actor {
  	def receive = {
  		case ShutDownSequence(message) => 
  			println(s"${RED}${BLINK}Server shutdown sequence started.\n$message${RESET}")
  			context.system.shutdown()
  	}
  }

  //the actor system
	val actorSystem = ActorSystem("muse-system")

  //creates an instance of the shutdown actor
 	val shutdown = actorSystem.actorOf(Props[ShutDownActor], "shutdown")
  
  //an actor that will broadcast messages to all registered websockets
	val broadcaster = actorSystem.actorOf(Props[WebSocketBroadcaster], "broadcaster")

  //actor that exchanges commands and results to the GUI 
	val player = actorSystem.actorOf(Props[PlayerActor], "player")
	//actor that interacts with the virtual world state (read/writes)
	val engine = actorSystem.actorOf(Props[WorldEngine], "engine")
	
	//standard socko configuration for handling routes to static files
	val routerConfig = StaticContentHandlerConfig()
	
	/*
	 * Creates an actor to deliver static content from the server
	 * the StaticContentHandler is part of the socko library of standard handlers
	 * @see http://sockoweb.org/docs/0.3.0/api/#org.mashupbots.socko.handlers.StaticContentHandler
	 */
	val staticRouter = actorSystem.actorOf(Props(new StaticContentHandler(routerConfig))
		.withRouter(FromConfig())                       //actor routing from configuration
		.withDispatcher("static-router-dispatcher")     //message dispatching from configuration
		, "static-file-router"
	)

  /*
   * this value defines use cases corresponding to the browser/client 
   * requesting different kind of resources through different url routes 
   */
	val routes = Routes {
		//handles  handshake requests for upgrades to the websocket protocol
		case WebSocketHandshake(handshake) => handshake match {
			//pattern match on a websocket url to identify the chosen player name
			case PathSegments("websocket" :: playerName :: Nil)  => {

				//define a callback to execute once the websocket auth completes
				val registrationCallback = (event: WebSocketHandshakeEvent) => {
					//register the new channel to the broadcaster actor
					broadcaster ! new WebSocketBroadcasterRegistration(event)

					//informs the player actor that a player has connected
					player ! Player.Connect(playerName, event.channel)
				}

				handshake.authorize(
					//register the callback on auth completion
					onComplete = Some(registrationCallback)
				)

			}
		}
		//handles a message frame coming from the websocket
		case WebSocketFrame(frame) => frame match {
			//the url is used to identify the messaging player (i.e. the client)
			case PathSegments("websocket" :: playerName :: Nil) => 
			//pass the message payload to the player actor
			player ! Player.Message(playerName, frame)
		}
		//handles the http requests
		case HttpRequest(request) => request match {
			//the path to the single application page
			case GET(Path("/main"))	                        => staticRouter ! new StaticResourceRequest(request, "mainPage.html")
			//the stylesheet
			case GET(PathSegments("css" :: cssFile :: Nil)) => staticRouter ! new StaticResourceRequest(request, "css/" + cssFile)
			//the javascript libraries
			case GET(PathSegments("js" :: jsFile :: Nil))   => staticRouter ! new StaticResourceRequest(request, "js/" + jsFile)
			//images
			case GET(PathSegments("img" :: imgFile :: Nil)) => staticRouter ! new StaticResourceRequest(request, "img/" + imgFile)
			//favicon, ignored
			case GET(Path("/favicon.ico"))                  => request.response.write(HttpResponseStatus.NOT_FOUND)
			//the user sent a disconnect request from the game
			case POST(Path("/disconnect"))                  =>
			  //signal the player actor of a user disconnecting 
				player ! Player.Disconnect(request.request.content.toString)
				//respond to the http request
				request.response.write(HttpResponseStatus.OK)
		}

	}

  //use the routes, actor system and defaul web configuration to start a socko web server
	val server = new WebServer(WebServerConfig(), routes, actorSystem)

  server.start()

  //if the actor system should be stopped than we stop the web server, too
  actorSystem.registerOnTermination {
  	println("actor system is down")
  	server.stop()
  }

  //console status: server online
  println(s"Server ${GREEN}online ${RESET}on localhost at port 8888.\nPress ${RED}enter to stop${RESET}")

  //wait on the console until an input line is received
  readLine()

  /*
   * on console input a shutdown request is sent to the shutdown actor
   * this will initiate the shutdown sequence
   */
  shutdown ! ShutDownSequence("A request from the command-line stopped the system")

}