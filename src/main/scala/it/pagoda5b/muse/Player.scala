package it.pagoda5b.muse

import akka.actor._
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.mashupbots.socko.handlers.WebSocketBroadcastText

class PlayerActor extends Actor {
	import Player._


	var channelsRegistry = Map[UserName, Channel]()

	def receive = {
		case Connect(user, chan) => 
			channelsRegistry += (user -> chan)
			context.actorFor("/user/engine") ! AddPlayer(user)
		case Disconnect(user) => 
			channelsRegistry -= user
			context.actorFor("/user/engine") ! RemovePlayer(user)
		case Message(user, frame) =>
			parseCommand(user, frame.readText) match {
				case Broadcast(msg) => 
					context.actorFor("/user/broadcaster") ! WebSocketBroadcastText(msg)
				case command => context.actorFor("/user/engine") ! command
			}
			// channelsRegistry.get(user) foreach { chan =>
			// 	chan.write(new TextWebSocketFrame(s"[on the registered channel] I received $msg"))
			// }
			// frame.writeText(s"[on the frame channel] I received $msg")
		case PlayerUpdates(updates) =>
			for (
				(user, text) <- updates.par;
				chan <- channelsRegistry.get(user)
			) {
				chan.write(new TextWebSocketFrame(text))
			}
	}

}

object Player {

	type UserName = String

	//socket messages
	case class Connect(username: UserName, wsChannel: Channel)
	case class Message(username: UserName, wsFrame: WebSocketFrameEvent)
	case class Disconnect(username: UserName)

	//engine messages
	case class PlayerUpdates(updates: List[(UserName, String)])

	//player commands
	abstract sealed class GameCommand(user: UserName = "")
	case class AddPlayer(user: UserName) extends GameCommand(user)
	case class RemovePlayer(user: UserName) extends GameCommand(user)
	case class DescribeMe(user: UserName, description: String) extends GameCommand(user)
	case class LookAround(user: UserName) extends GameCommand(user)
	case class GoToExit(user: UserName, exit: String) extends GameCommand(user)
	case class Perform(user: UserName, action: String) extends GameCommand(user)
	case class Broadcast(message: String) extends GameCommand()
	case object Report extends GameCommand()

	val describeMeSyntax = """^ME\s+(.+)""".r
	val lookAroundSyntax = "LOOK"
	val goToExitSyntax = """^GO\s+(.+)""".r
	val broadcastSyntax = """^--BROADCAST\s+(.+)""".r
	val reportSyntax = "--REPORT"

	def parseCommand(user: UserName, cmd: String): GameCommand = 
		cmd match {
			case `lookAroundSyntax` => LookAround(user)
			case `reportSyntax` => Report
			case describeMeSyntax(desc) => DescribeMe(user, desc)
			case goToExitSyntax(exit) => GoToExit(user, exit)
			case broadcastSyntax(message) => Broadcast(message)
			case action => Perform(user, action)
		}
		

}
