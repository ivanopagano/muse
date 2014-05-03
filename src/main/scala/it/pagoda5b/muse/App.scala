package it.pagoda5b.muse

import org.mashupbots.socko._
import org.mashupbots.socko.webserver._
import org.mashupbots.socko.routes._
import org.mashupbots.socko.handlers._
import org.mashupbots.socko.events._
import akka.actor._
import akka.routing.FromConfig
import Console._
import scala.sys.props

object MuseServer extends App {

	case class ShutDownSequence(reason: String)

  class ShutDownActor extends Actor {
  	def receive = {
  		case ShutDownSequence(message) => 
  			println(s"${RED}${BLINK}Server shutdown sequence started.\n$message${RESET}")
  			context.system.shutdown()
  	}
  }

	val actorSystem = ActorSystem("muse-system")

 	val shutdown = actorSystem.actorOf(Props[ShutDownActor], "shutdown")

	val broadcaster = actorSystem.actorOf(Props[WebSocketBroadcaster], "broadcaster")
	
	val player = actorSystem.actorOf(Props[PlayerActor], "player")
	val engine = actorSystem.actorOf(Props[WorldEngine], "engine")
	
	val routerConfig = StaticContentHandlerConfig()
	
	val staticRouter = actorSystem.actorOf(Props(new StaticContentHandler(routerConfig))
		.withRouter(FromConfig())
		.withDispatcher("static-router-dispatcher")
		, "static-file-router"
	)

	val routes = Routes {
		case WebSocketHandshake(handshake) => handshake match {
			case PathSegments("websocket" :: playerName :: Nil)  => {
				val registrationCallback = (event: WebSocketHandshakeEvent) => {
						broadcaster ! new WebSocketBroadcasterRegistration(event)
						player ! Player.Connect(playerName, event.channel)
				}

				handshake.authorize(
					onComplete = Some(registrationCallback)
				)

			}
		}
		case WebSocketFrame(frame) => frame match {
			case PathSegments("websocket" :: playerName :: Nil) => 
			player ! Player.Message(playerName, frame)
		}
		case HttpRequest(request) => request match {
			case GET(Path("/main"))	                        => staticRouter ! new StaticResourceRequest(request, "mainPage.html")
			case GET(PathSegments("css" :: cssFile :: Nil)) => staticRouter ! new StaticResourceRequest(request, "css/" + cssFile)
			case GET(PathSegments("js" :: jsFile :: Nil))   => staticRouter ! new StaticResourceRequest(request, "js/" + jsFile)
			case GET(PathSegments("img" :: imgFile :: Nil)) => staticRouter ! new StaticResourceRequest(request, "img/" + imgFile)
			case GET(Path("/favicon.ico"))                  => request.response.write(HttpResponseStatus.NOT_FOUND)
			case POST(Path("/disconnect"))                  => 
				player ! Player.Disconnect(request.request.content.toString)
				request.response.write(HttpResponseStatus.OK)
		}

	}

	val port: Int = props.get("app.port").map(_.toInt).getOrElse(8888)

	val server = new WebServer(WebServerConfig(hostname = "0.0.0.0", port = port), routes, actorSystem)

  server.start()

  actorSystem.registerOnTermination {
  	println("actor system is down")
  	server.stop()
  }

  println(s"Server ${GREEN}online ${RESET}on localhost at port $port.\nPress ${RED}enter to stop${RESET}")

  readLine()

  shutdown ! ShutDownSequence("A request from the command-line stopped the system")

}