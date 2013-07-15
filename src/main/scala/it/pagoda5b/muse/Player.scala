package it.pagoda5b.muse

import akka.actor._
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.mashupbots.socko.handlers.WebSocketBroadcastText

object Player {

	case class Connect(username: String, wsChannel: Channel)
	case class Message(username: String, wsFrame: WebSocketFrameEvent)
	
}

class PlayerActor extends Actor {
	import Player._


	var channelsRegistry = Map[String, Channel]()

	def receive = {
		case Connect(user, chan) => 
			channelsRegistry += (user -> chan)
		case Message(user, frame) => 
			val msg = frame.readText
			channelsRegistry.get(user) foreach { chan =>
				chan.write(new TextWebSocketFrame(s"[on the registered channel] I received $msg"))
			}
			frame.writeText(s"[on the frame channel] I received $msg")
			context.actorFor("/user/broadcaster") ! WebSocketBroadcastText(s"[on the broadcast channel] I received $msg")
	}

}