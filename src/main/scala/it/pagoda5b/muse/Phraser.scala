package it.pagoda5b.muse

import akka.actor._

/*
 * this actor translates event objects with parameters into a readable 
 * text message that can be sent to the ui
 */
class PhraserActor extends Actor {
	import Phraser._
	import Localizer._
	
	def receive = {
		case NewPlayer(desc) => 
			sender ! newPlayerMessage(desc)
		case DescribeRoom((roomName, roomDesc), exits, people) => 
			sender ! describeRoomMessage(roomName, roomDesc, describeExits(exits), describePeople(people))
		case PlayerAction(player, action) => 
			sender ! playerActionMessage(player, action)
		case PlayerIncoming(player, (exitName, exitDesc)) => 
			sender ! playerIncomingMessage(player, exitDesc)
		case PlayerLeaving(player, (exitName, exitDesc)) =>
			sender ! playerLeavingMessage(player, exitDesc)
		case PlayerMoving((exitName, exitDesc)) =>
			sender ! playerMovingMessage(exitDesc)
		case NoiseFrom((exitName, exitDesc)) =>
			sender ! noiseFromMessage(exitDesc)
		case NoExit(exit) =>
			sender ! noExitMessage(exit)
		case PlayerDescribed =>
			sender ! playerDescribedMessage
	}

	def describePeople(people: List[String]) = 
		if(people.isEmpty) ""
		else people.mkString(localize("phrase.describePeople"), ", ", ".\n")

	def describeExits(exits: List[(ExitName, String)]) = 
		if(exits.isEmpty) ""
		else exits map {
			case (id, desc) => s"[$id] $desc"
		} mkString(localize("phrase.describeExits"), "\n", ".")

}

object Phraser {
  
  //a useful type alias to enhance readability
	type ExitName = String

	//events corresponding to sentences needed by the game
	sealed trait GameEvent
	case class NewPlayer(desc: String) extends GameEvent
	case class DescribeRoom(room: (String, String), exits: List[(ExitName, String)], people: List[String]) extends GameEvent
	case class PlayerAction(player: String, action: String) extends GameEvent
	case class PlayerIncoming(player: String, exit: (ExitName, String)) extends GameEvent
	case class PlayerLeaving(player: String, exit: (ExitName, String)) extends GameEvent
	case class PlayerMoving(exit: (ExitName, String)) extends GameEvent
	case class NoiseFrom(exit: (ExitName, String)) extends GameEvent
	case class NoExit(exit: ExitName) extends GameEvent
	case object PlayerDescribed extends GameEvent
	case object NoOp extends GameEvent

}

/*
 * This singleton takes care to localize all available messages using resource bundles.
 * The language selection is made by a lookup to a specific environment property: it.pagoda5b.muse.locale
 * The default language is italian
 */
object Localizer {
  import scala.sys.props
  import com.typesafe.config._

  private def locale = props.getOrElse("it.pagoda5b.muse.locale", "it")
	private lazy val config = ConfigFactory.load(s"phrase-replies-$locale")

	//generic message localizer
	def localize(message: String, args: String*): String = {
		val template = config.getString(message)
		if (args.isEmpty) 
			template
		else
			template.format(args: _*)
	}

	//default description for logged-in player's avatar
	def defaultPlayerDescription = localize("default.playerDescription")
	
	//specific phraser messages
	def newPlayerMessage(desc: String) = localize("phrase.newPlayer", desc)

	def describeRoomMessage(roomName: String, roomDesc: String, exits: String, people: String) =
		localize("phrase.describeRoom", roomName, roomDesc, people, exits)

	def playerActionMessage(player: String, action: String) = localize("phrase.playerAction", player, action)

	def playerIncomingMessage(player: String, exit: String) = localize("phrase.playerIncoming", player, exit)
	
	def playerLeavingMessage(player: String, exit: String) = localize("phrase.playerLeaving", player, exit)

	def playerMovingMessage(exit: String) = localize("phrase.playerMoving", exit)
	
	def noiseFromMessage(exit: String) = localize("phrase.noiseFrom", exit)

	def noExitMessage(exit: String) = localize("phrase.noExit", exit)

	def playerDescribedMessage = localize("phrase.playerDescribed")

}