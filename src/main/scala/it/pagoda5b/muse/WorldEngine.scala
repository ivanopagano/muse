package it.pagoda5b.muse

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.routing.FromConfig
import akka.util.Timeout
import scala.util.{Try, Success, Failure}
import scala.util.Try._
import scala.util.Properties
import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext}
import org.neo4j.graphdb._
import Player.UserName
import Phraser._

class WorldEngine extends Actor {
	import WorldEngine._
	import Player._


	private val responseActor = context.actorOf(Props[ResponseDeliveryActor], "responder")
	implicit val graphExecutor = context.system.dispatchers.lookup("graph-access-dispatcher")

	private val world = WorldGraph()

	def receive = {
		case AddPlayer(player) =>
			val updates = world.addPlayer(player)
			pipe(updates) to responseActor
		case RemovePlayer(player) =>
			world.removePlayer(player)
		case DescribeMe(player, desc) =>
			val event = world.changeDescription(player, desc)
			val update = event.map(eventFor(_, player))
			pipe(update) to responseActor
		case LookAround(player) =>
			val desc = world.getRoomDescription(player)
			val update = desc.map(eventFor(_, player))
			pipe(update) to responseActor
		case GoToExit(player, exit) =>
			val updates = world.goTo(player, exit)
			pipe(updates) to responseActor
			updates onSuccess { case _ =>
				self ! LookAround(player)
			}
		case Perform(player, action) =>
			val updates = world.perform(player, action)
			pipe(updates) to responseActor
		case Report =>
			world.report
		case _ => 
			//default case
			unhandled()
	}

	override def postStop() {
		world.stop()
	}

}

//specialized actor to deliver feedback updates to the user interface
class ResponseDeliveryActor extends Actor {
	import WorldEngine.UpdateEvents
	import Player._
	import scala.concurrent.ExecutionContext.Implicits._

	
	private val phraser = context.actorOf(Props[PhraserActor].withRouter(FromConfig()), "phraser")
	private val playerActor = context.actorFor("/user/player")
	//timeout for async operations 
	implicit val timeout = Timeout(5 seconds)

	def receive = {
		case (`NoOp`, _) => 
			//nothing to tell
		case (event: GameEvent, users: List[UserName]) =>
			//single event
			deliver(event, users)
		case events: UpdateEvents =>
			//many events
			events foreach {
				case (event, users) =>
					deliver(event, users)
			}
		case _ =>
			unhandled()
	}

	private def deliver(event: GameEvent, users: List[UserName]): Unit = {
			//a future response with the phrase converted to NL text
			val response = for {
				text <- (phraser ? event).mapTo[String]
			} yield PlayerUpdates(users zip Stream.continually(text))

			pipe(response) to playerActor
	}

}

object WorldEngine {
	//alias for a complex response type
	type UpdateEvents = List[(GameEvent, List[UserName])]

	//utility to create an update event for a single player with less typing
	def eventFor(event: GameEvent, player: UserName) = (event, List(player))

	//empty list of ui updates for failure cases
	val noUpdates: Future[UpdateEvents] = Future.successful[UpdateEvents](Nil)
}

private[muse] class WorldGraph(graph: GraphDatabaseService) {
	import org.neo4j.graphdb._
	import org.neo4j.graphdb.index._
	import org.neo4j.cypher.javacompat.ExecutionEngine
	import WorldGraph._
	import GraphSearch._
	import WorldEngine._

	private val playersIdx: Index[Node] = graph.index.forNodes("Players")
	private implicit val queryEngine = new ExecutionEngine(graph)

	private val startRoom: Long = WorldInstances.SimpleTestWorld.populate(graph).map(_.getId).getOrElse(0L)

	def stop(): Unit = graph.shutdown()

	def putIn(player: Node, room: Node): Unit = {
		def where = {
			val in = player.getSingleRelationship(IS_IN, Direction.OUTGOING)
			nodeProperties(in.getEndNode)._1
		}
		if (player.hasRelationship) {
			player.getSingleRelationship(IS_IN, Direction.OUTGOING).delete()
		}
		player.createRelationshipTo(room, IS_IN)
	}

	def addPlayer(player: UserName)(implicit executor: ExecutionContext): Future[UpdateEvents] = {

		//Tries to update the world graph
		def added: Try[Node] = transacted(graph) { g =>

			val pl = g.createNode
			pl.setProperty("name", player)
			pl.setProperty("description", "uno sconosciuto")

			playersIdx.add(pl, "name", player)

			putIn(pl, g.getNodeById(startRoom))

			pl
		}

		//prepare feedback messages for the involved players
		def updates(playerAdded: Node): Future[UpdateEvents] =
			for {
				//the following calls are made concurrently, wrapped in future objects and then combined
				//find the room
				r <- roomWith(player)
				//find players in the same room
				bs <- sameRoomWith(player)
				//map to properties 
				(room, exits, bystanders) = (nodeProperties(r), roomExits(r).map(exitProperties), bs.map(nodeProperties))
				//prepare phrases for player and people in the same room
				(playerPhrase, bystandersPhrase) = (DescribeRoom(room, exits, bystanders.map(_._2)), NewPlayer(nodeProperties(playerAdded)._2))
				//pack messages for phraser
			} yield eventFor(playerPhrase, player) :: (bystandersPhrase, bystanders.map(_._1)) :: Nil

		//map on the eventual try result
		val feedbacks = added.map{updates(_)}
		//no feedback if there's been an error		
		feedbacks.getOrElse(noUpdates)
	}

	def removePlayer(player: UserName)(implicit executor: ExecutionContext): Unit = 
		self(player) foreach { pl => 
			transacted(graph) { g =>
				import scala.collection.JavaConversions._
				
				playersIdx.remove(pl)
				pl.getRelationships(Direction.OUTGOING) foreach {_.delete()}
				pl.delete()
			}
		}


	def changeDescription(player: UserName, description: String)(implicit executor: ExecutionContext): Future[GameEvent] =
		for {
			pl <- self(player)
		} yield {
			val update = transacted(graph) { g =>
				pl.setProperty("description", description)
				PlayerDescribed
			}
			update.getOrElse(NoOp)
		}

	def getRoomDescription(player: UserName)(implicit executor: ExecutionContext): Future[GameEvent] = 
		for {
			//find the room
			r <- roomWith(player)
			//find players in the same room
			bs <- sameRoomWith(player)
			//extract readable properties
			(room, exits, bystanders) = (nodeProperties(r), roomExits(r).map(exitProperties), bs.map(nodeProperties))
			//fetch data for room description
		} yield DescribeRoom(room, exits, bystanders.map(_._2))

	def perform(player: UserName, action: String)(implicit executor: ExecutionContext): Future[UpdateEvents] = {
		def collapseNeighbours(l: List[(Relationship, Node)]): List[UserName] =
			l map {
				case (r, n) => nodeProperties(n)._1
			}

		for {
			//get the acting player
			actor <- self(player)
			//find players in the same room
			sameRoom <- sameRoomWith(player)
			//find players in the nearby rooms
			nextRoom <- nextDoorsTo(player)	
			//describe action for actor
			actorPhrase = eventFor(PlayerAction(player, action), player)
			//action for people in the same room
			sameRoomPhrase = (PlayerAction(nodeProperties(actor)._2, action),  sameRoom.map(nodeProperties(_)._1))
			//noises heard by people in the room next door
			//1. group by room
			nextRoomGroups: Map[(String, String), List[(Relationship, Node)]] = nextRoom groupBy {
				case (exitRel, people) => exitProperties(exitRel)
			}
			//2. define response for each room group
			nextRoomPhrase = nextRoomGroups.foldLeft(List.empty[(GameEvent, List[UserName])]) {
				case (result, (exit, nr)) => (NoiseFrom(exit), collapseNeighbours(nr)) :: result
			}
		} yield actorPhrase :: sameRoomPhrase :: nextRoomPhrase

	}

	def goTo(player: UserName, id: ExitName)(implicit executor: ExecutionContext): Future[UpdateEvents] = {

		def cross(exit: Option[Relationship]): Future[UpdateEvents] = exit match {
			case None => 
				//wrong exit id?
				Future {
					eventFor(NoExit(id), player) :: Nil
				}
			case Some(exitEdge) =>
				for {
					//get the moving player
					pl <- self(player)
					//get the room
					roomIn <- roomWith(player)
					//find players in the same room
					sameRoom <- sameRoomWith(player)
					//find players in the room across the exit
					//LOOK OUT: this value is optional!!
					nextRoom <- nextDoorsToThrough(player, id)
					//extract readable properties
					playerDesc = nodeProperties(pl)._2
					exit = exitProperties(exitEdge)
					bystanders = sameRoom map {
						nodeProperties
					}
					neighbours = nextRoom map {
						case (entrance, people) =>
						(exitProperties(entrance), people.map(nodeProperties))
					}
					//prepare the response events
					playerPhrase = eventFor(PlayerMoving(exit), player)
					leavingPhrase = (PlayerLeaving(playerDesc, exit), bystanders.map(_._1))
					//this value is an option, it depends on whether there's anyone on the destination room
					comingPhrase = neighbours map {
						case (entrance, people) =>
							(PlayerIncoming(playerDesc, entrance), people.map(_._1))
					}
				} yield playerPhrase :: leavingPhrase :: comingPhrase.toList

		}

		def updateGraph(exit: Relationship): Unit =
			for ( pl <- self(player) ) {
				transacted(graph) { _ => 
					putIn(pl, exit.getEndNode) 
				}
			}

		/*
		 * Note: a usually desired behavior is that after the move, a player also receives a description 
		 * of the room he's arrived into, just as if he issued a look command.
		 * The message delivery system is expected to handle that
		 */
		val move: Try[Future[UpdateEvents]] = 
			transacted(graph) { g =>

				for {
					e <- exitFor(player, id)
					updates <- cross(e)
					_ <- Future {
						e foreach { updateGraph }
					}
				} yield updates

			}

		move.getOrElse(noUpdates)
	}

	def report(implicit executor: ExecutionContext): Unit = {
		/****DEBUG SESSION****/
		val (ns, rs) = (allNodes, allRelations)
		ns onSuccess {
			case n: List[Node] => 
				val nodes = n.map(node => (node.getId -> nodeProperties(node)))

				println(nodes.mkString("NODES [\n", "\n", "\n]"))
		}
		rs onSuccess {
			case r: List[Relationship] => 
				val exits = r filter {
					_.hasProperty("name")
				} map { 
					case rel => s"edge ${rel.getId} ${nodeProperties(rel.getStartNode)._1}-[${exitProperties(rel)}]->${nodeProperties(rel.getEndNode)._1}"
				}

				val people = r filter {
					_.getType.name == "IS_IN"
				} map {
					case rel => s"edge ${rel.getId} ${nodeProperties(rel.getStartNode)._1} IS IN ${nodeProperties(rel.getEndNode)._1}"
				}

				println(exits.mkString("EXITS [\n", "\n", "\n]"))
				println(people.mkString("PEOPLE [\n", "\n", "\n]"))
		}
	}

}

private[muse] object WorldGraph {
	import org.neo4j.graphdb.factory._
	import com.typesafe.config._

	case object IS_IN extends RelationshipType {val name: String = "IS_IN"}
	case object LEADS_TO extends RelationshipType {val name: String = "LEADS_TO"}

	//companion constructor
	def apply(): WorldGraph = {
		val conf = ConfigFactory.load().getConfig("world-engine")

		val storageDir = Properties.tmpDir + conf.getString("graph-dir")
		val graph: GraphDatabaseService = (new GraphDatabaseFactory).newEmbeddedDatabase(storageDir)
		new WorldGraph(graph)
	}

	def transacted[A](g: GraphDatabaseService)(op: GraphDatabaseService => A): Try[A] =  {
		val tx = g.beginTx()
		val result = Try {
			val r = op(g)
			tx.success()
			r
		}
		tx.finish()
		result
	}

	private def nodeProperties(n: Node): (String, String) = (n.getProperty("name").toString, n.getProperty("description").toString)

	private def exitProperties(e: Relationship): (ExitName, String) = (e.getProperty("name").toString, e.getProperty("description").toString)

	private object GraphSearch {
		import org.neo4j.graphdb._
		import org.neo4j.cypher.javacompat.ExecutionEngine
		import scala.collection.JavaConversions._
		import scala.concurrent.Future

		private def selfNode(player: UserName): String =
			s"""start p=node:Players(name="$player") 
				| return p""".stripMargin
		
		private def room(player: UserName): String = 
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)
				| return r""".stripMargin

		private def sameRoom(player: UserName): String = 
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[:IS_IN]-(other)
				| where other <> p
				| return other""".stripMargin

		private def nextRooms(player: UserName): String = 
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[:LEADS_TO]-(r2)<-[:IS_IN]-(other)
				| return other""".stripMargin

		private def nextDoors(player: UserName): String =
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[exit:LEADS_TO]-(r2)<-[:IS_IN]-(other)
				| return exit, other""".stripMargin

		private def nextDoorsThrough(player: UserName, id: ExitName): String =
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)-[exit:LEADS_TO]->(r2)<-[:IS_IN]-(other),
				| (r)<-[entrance:LEADS_TO]-(r2)
				| where exit.name = "$id"
				| return entrance, other""".stripMargin

		private def exitWithIdFor(player: UserName, id: ExitName): String =
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)-[exit:LEADS_TO]->(r2)
				| where exit.name = "$id"
				| return exit""".stripMargin

		//this is mostly for testing purposes
		def allNodes(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute("start n=node(*) return n").columnAs("n").toList
		}

		//this is mostly for testing purposes
		def allRelations(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Relationship]] = Future {
			engine.execute("start n=node(*) match (n)-[r]-(n2) return distinct r").columnAs("r").toList
		}

		def self(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Node] = Future {
			engine.execute(selfNode(player)).columnAs("p").next.asInstanceOf[Node]
		}

		def roomWith(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Node] = Future {
			engine.execute(room(player)).columnAs("r").next.asInstanceOf[Node]
		}

		def sameRoomWith(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute(sameRoom(player)).columnAs("other").toList
		}

		def nextRoomsTo(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute(nextRooms(player)).columnAs("other").toList
		}

		def nextDoorsTo(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[(Relationship, Node)]] = Future {
			val rows = engine.execute(nextDoors(player))
			(rows map (r => (r("exit").asInstanceOf[Relationship], r("other").asInstanceOf[Node]))).toList
		}

		def nextDoorsToThrough(player: UserName, id: ExitName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Option[(Relationship, List[Node])]] = Future {

			val rows = engine.execute(nextDoorsThrough(player, id))
			val neighbour = (rows map (r => (r("entrance").asInstanceOf[Relationship], r("other").asInstanceOf[Node]))).toList
			neighbour match {
				case Nil => None
				case list => 
					val (entrances, people) = list.unzip
					Some((entrances.head, people))
			}
			
		}

		def exitFor(player: UserName, id: ExitName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Option[Relationship]] = Future {
			engine.execute(exitWithIdFor(player, id)).columnAs("exit").toList.headOption
		}

		def roomExits(room: Node): List[Relationship] =
			room.getRelationships(Direction.OUTGOING, WorldGraph.LEADS_TO).toList

		def exitDestination(exit: Relationship): Node =
			exit.getEndNode


	}
}

