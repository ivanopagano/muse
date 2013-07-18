package it.pagoda5b.muse

import akka.actor._

class PhraserActor extends Actor {
	import Phraser._
	
	def receive = {
		case PlayerAdded(desc) => 
			sender ! s"Ti accorgi ora che con te c'e' $desc"
		case DescribeRoom((roomName, roomDesc), exits, people) => 
			sender ! s"""Sei in $roomName. $roomDesc.
				 					|${describePeople(people)} ${describeExits(exits)}""".stripMargin
		case PlayerAction(player, action) => 
			sender ! s"$player $action"
		case PlayerComes(player, (exitId, exitDesc)) => 
			sender ! s"$player arriva da $exitDesc"
		case PlayerLeaves(player, (exitId, exitDesc)) =>
			sender ! (s"attraversi $exitDesc", s"$player esce da $exitDesc")
		case NoiseFrom((exitId, exitDesc)) =>
			sender ! (s"puoi sentire dei rumori provenire oltre $exitDesc")
	}

	def describePeople(people: List[String]) = 
		if(people.isEmpty) ""
		else people.mkString("Assieme a te puoi vedere ", ", ", ".\n")

	def describeExits(exits: List[(ExitId, String)]) = 
		if(exits.isEmpty) ""
		else exits map {
			case (id, desc) => s"[$id] $desc"
		} mkString("Puoi notare le seguenti uscite:\n", "\n", ".")

}

object Phraser {

	type ExitId = String

	//messages corresponding to sentences needed by the game
	abstract sealed class GameEvent
	case class PlayerAdded(desc: String) extends GameEvent
	case class DescribeRoom(room: (String, String), exits: List[(ExitId, String)], people: List[String]) extends GameEvent
	case class PlayerAction(player: String, action: String) extends GameEvent
	case class PlayerComes(player: String, exit: (ExitId, String)) extends GameEvent
	case class PlayerLeaves(player: String, exit: (ExitId, String)) extends GameEvent
	case class NoiseFrom(exit: (ExitId, String)) extends GameEvent

}