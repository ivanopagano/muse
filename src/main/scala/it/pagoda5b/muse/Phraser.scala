package it.pagoda5b.muse

import akka.actor._

class PhraserActor extends Actor {
	import Phraser._
	
	def receive = {
		case NewPlayer(desc) => 
			sender ! s"Ti accorgi ora che con te c'e' $desc"
		case DescribeRoom((roomName, roomDesc), exits, people) => 
			sender ! s"""Sei in $roomName. $roomDesc.
				 					|${describePeople(people)}${describeExits(exits)}""".stripMargin
		case PlayerAction(player, action) => 
			sender ! s"$player $action"
		case PlayerIncoming(player, (exitName, exitDesc)) => 
			sender ! s"$player arriva da $exitDesc"
		case PlayerLeaving(player, (exitName, exitDesc)) =>
			sender ! s"$player esce da $exitDesc"
		case PlayerMoving((exitName, exitDesc)) =>
			sender ! s"attraversi $exitDesc"
		case NoiseFrom((exitName, exitDesc)) =>
			sender ! s"puoi sentire dei rumori provenire da oltre $exitDesc"
		case NoExit(exit) =>
			sender ! s"non vedi $exit, dove vorresti passare?"
		case PlayerDescribed =>
			sender ! "ora gli altri ti guarderanno con occhi diversi?"
	}

	def describePeople(people: List[String]) = 
		if(people.isEmpty) ""
		else people.mkString("Assieme a te puoi vedere ", ", ", ".\n")

	def describeExits(exits: List[(ExitName, String)]) = 
		if(exits.isEmpty) ""
		else exits map {
			case (id, desc) => s"[$id] $desc"
		} mkString("Puoi notare le seguenti uscite:\n", "\n", ".")

}

object Phraser {

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