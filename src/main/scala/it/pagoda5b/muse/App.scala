package it.pagoda5b.muse

import org.mashupbots.socko._
import org.mashupbots.socko.webserver._
import org.mashupbots.socko.routes._
import org.mashupbots.socko.handlers._
import org.mashupbots.socko.events._
import akka.actor._
import akka.routing.FromConfig
import Console._
import java.io.File

object MuseServer extends App {

	val actorSystem = ActorSystem("muse-system")

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
			case GET(Path("/main")) 												=> staticRouter ! new StaticResourceRequest(request, "mainPage.html")
			case GET(PathSegments("css" :: cssFile :: Nil)) => staticRouter ! new StaticResourceRequest(request, "css/" + cssFile)
			case GET(PathSegments("js" :: jsFile :: Nil)) 	=> staticRouter ! new StaticResourceRequest(request, "js/" + jsFile)
			case GET(PathSegments("img" :: imgFile :: Nil)) 	=> staticRouter ! new StaticResourceRequest(request, "img/" + imgFile)
			case GET(Path("/favicon.ico")) 									=> request.response.write(HttpResponseStatus.NOT_FOUND)
		}
		
	}

	val server = new WebServer(WebServerConfig(), routes, actorSystem)

  server.start()

  println(s"Server ${GREEN}online ${RESET}on localhost at port 8888.\nPress ${RED}enter to stop${RESET}")

  readLine()

  server.stop()

  actorSystem.shutdown()

}