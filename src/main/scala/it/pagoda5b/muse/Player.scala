package it.pagoda5b.muse

import akka.actor._
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.mashupbots.socko.handlers.WebSocketBroadcastText

/**
 * This actor receives text commands from the ui
 * delivered through the websocket channels
 *
 * It's role is to parse the command and send a corresponding message
 * to the game engine.
 * It also take notes of the players currently logged in the virtual world
 */
class PlayerActor extends Actor {
	import Player._

  //logged-in users and related websocket channel
	var channelsRegistry = Map[UserName, Channel]()

	def receive = {
		case Connect(user, chan) => 
  		//a user connected
		  //register the user with his ws channel
			channelsRegistry += (user -> chan)
			//signal the engine that a new player logged in
			context.actorFor("/user/engine") ! AddPlayer(user)
		case Disconnect(user) => 
		  //user left
		  //remove his channel from registry
			channelsRegistry -= user
			//signal the engine that the player is no more
			context.actorFor("/user/engine") ! RemovePlayer(user)
		case Message(user, frame) =>
		  //user sent a text message from the ui, parse it and use the result
			parseCommand(user, frame.readText) match {
				//it's a broadcast request, deliver the message to the broadcasting actor
				case Broadcast(msg) => 
					context.actorFor("/user/broadcaster") ! WebSocketBroadcastText(msg)
				//it's a command, forward it to the engine as is
				case command => context.actorFor("/user/engine") ! command
			}
			// we can deliver messages directly to all registered channels
			// channelsRegistry.get(user) foreach { chan =>
			// 	chan.write(new TextWebSocketFrame(s"[on the registered channel] I received $msg"))
			// }
			// or write directly through the socket frame to the specific channel
			// frame.writeText(s"[on the frame channel] I received $msg")
		case PlayerUpdates(updates) =>
		  //there is some text to deliver to the ui
			for (
				/*
				 * updates is a list containing users and a related feedback message each
				 * read the list in parallel (using multicore if available)
				 * and write on each ws channel a text frame
				 */
				(user, text) <- updates.par;
				chan <- channelsRegistry.get(user)
			) {
				chan.write(new TextWebSocketFrame(text))
			}
	}

}

/**
 * this module contains available messages and defines command parsing
 */
object Player {

  //use a type alias to enhance readability
	type UserName = String

	//socket messages
	case class Connect(username: UserName, wsChannel: Channel)
	case class Message(username: UserName, wsFrame: WebSocketFrameEvent)
	case class Disconnect(username: UserName)

	//engine messages
	case class PlayerUpdates(updates: List[(UserName, String)])

	//player commands
	//the base class expects the user that sent the command
	abstract sealed class GameCommand(user: UserName = "")
	//a player logged-in
	case class AddPlayer(user: UserName) extends GameCommand(user)
	//the player logged-out
	case class RemovePlayer(user: UserName) extends GameCommand(user)
	//define a new description for the player's avatar (to be seen from the other players)
	case class DescribeMe(user: UserName, description: String) extends GameCommand(user)
	//player requests a description of his whereabouts
	case class LookAround(user: UserName) extends GameCommand(user)
	//player wants to cross one of the exits of his current location
	case class GoToExit(user: UserName, exit: String) extends GameCommand(user)
	//player is doing something, actions are free text that will be seen as related to the player's avatar
	case class Perform(user: UserName, action: String) extends GameCommand(user)
	//player sent a broadcast request, all connected players will see this message
	case class Broadcast(message: String) extends GameCommand()
	//debug report on the status of the virtual world, output delivered on the console
	case object Report extends GameCommand()

  //the following regexp defines the different textual commands available
	private[this] val describeMeSyntax = """^IS\s+(.+)""".r
	private[this] val lookAroundSyntax = "LOOK"
	private[this] val goToExitSyntax = """^GO\s+(.+)""".r
	private[this] val broadcastSyntax = """^--BROADCAST\s+(.+)""".r
	private[this] val reportSyntax = "--REPORT"

  /*
   * parse the text received from the ui to 
   * identify the command
   */
	def parseCommand(user: UserName, cmd: String): GameCommand = 
		cmd match {
			// the backticks are needed when there's no parameter, to match on the val symbols defined above instead of matching any input and binding it to the RHS
			case `lookAroundSyntax` => LookAround(user) 
			case `reportSyntax` => Report
			case describeMeSyntax(desc) => DescribeMe(user, desc)
			case goToExitSyntax(exit) => GoToExit(user, exit)
			case broadcastSyntax(message) => Broadcast(message)
			case action => Perform(user, action)
		}
		

}
