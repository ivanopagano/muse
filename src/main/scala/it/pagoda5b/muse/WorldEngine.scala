package it.pagoda5b.muse

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.util.{Try, Success, Failure}
import scala.util.Try._
import scala.util.Properties
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import Player.UserName
import Phraser._

class WorldEngine extends Actor {
	import Player._
	import akka.routing.FromConfig

	private val phraser = context.actorOf(Props[PhraserActor].withRouter(FromConfig()), "phraser")
	private val playerActor = context.actorFor("/user/player")

	def receive = {
		case AddPlayer(player) =>
			val updates = WorldGraph.addPlayer(player)
			updates.par foreach deliverResponse
		case RemovePlayer(player) =>
			WorldGraph.removePlayer(player)
		case _ => 
			//default case
	}

	def deliverResponse(event: (GameEvent, List[UserName])): Unit = event match {
		case (event, users) =>
			implicit val timeout = Timeout(5 seconds)
			//a future response
			val phrase = (phraser ? event).mapTo[String]
			phrase onComplete {
				case Success(text) =>
					playerActor ! PlayerUpdates(users zip Stream.continually(text))
				case Failure(error) =>
					//do something?
			}
	}

	override def postStop() {
		WorldGraph.stop()
	}

}

object WorldEngine {

	type UpdateEvents = List[(GameEvent, List[UserName])]

}

private[muse] object WorldGraph {
	import org.neo4j.graphdb._
	import org.neo4j.graphdb.index._
	import org.neo4j.graphdb.factory._
	import org.neo4j.cypher.javacompat.ExecutionEngine
	import WorldGraph._
	import WorldEngine._
	import GraphSearch._
	import com.typesafe.config._

	val conf = ConfigFactory.load().getConfig("world-engine")


	private val STORAGE_DIR = Properties.tmpDir + conf.getString("graph-dir")
	private val graph: GraphDatabaseService = (new GraphDatabaseFactory).newEmbeddedDatabase(STORAGE_DIR)
	private val playersIdx: Index[Node] = graph.index.forNodes("Players")

	private implicit val queryEngine = new ExecutionEngine(graph)

	private val startRoom: Long = populate(graph).map(_.getId).getOrElse(0L)

	case object IS_IN extends RelationshipType {val name: String = "IS_IN"}
	case object LEADS_TO extends RelationshipType {val name: String = "LEADS_TO"}

	def stop(): Unit = graph.shutdown()

	def addPlayer(player: UserName): UpdateEvents = {

		//Tries to update the world graph
		def added: Try[Node] = transacted(graph) { g =>

			val pl = g.createNode
			pl.setProperty("name", player)
			pl.setProperty("description", "uno sconosciuto")

			playersIdx.add(pl, "name", player)

			val start = g.getNodeById(startRoom)

			pl.createRelationshipTo(start, IS_IN)

			pl
		}

		//Tries prepare feedback messages for all the players
		def updates(playerAdded: Node): Try[UpdateEvents] = transacted(graph) { g =>
			//find the room
			val room = roomWith(player)
			//find players in the same room
			val bystanders = sameRoomWith(player).map(nodeDetails);
			//fetch data for room description
			val playerPhrase = DescribeRoom(nodeDetails(room), roomExits(room).map(exitDetails), bystanders.map(_._2))
			//fetch data for players in the same room
			val bystandersPhrase = NewPlayer(nodeDetails(playerAdded)._2)
			//pack messages for phraser
			List((playerPhrase, List(player)), (bystandersPhrase, bystanders.map(_._1)))
		}

		//combine the tries
		val feedbacks = for {
			p <- added
			phrases <- updates(p)
		} yield phrases

		feedbacks.getOrElse(List())
	}

	def removePlayer(player: UserName): Try[Unit] = transacted(graph) { g =>
		import scala.collection.JavaConversions._

		val pl = self(player)
		playersIdx.remove(pl)
		pl.getRelationships(Direction.OUTGOING) foreach {_.delete()}
		pl.delete()

	}

	def populate(g: GraphDatabaseService): Try[Node] = {
		def createRoom(name: String, desc: String): Node = {
			val room = g.createNode
			room.setProperty("name", name)
			room.setProperty("description", desc)
			room
		}

		def joinRooms(r1: Node, r2: Node, id: String, desc: String): Relationship = {
			val exit = r1.createRelationshipTo(r2, LEADS_TO)
			exit.setProperty("id", id)
			exit.setProperty("description", desc)
			exit
		}

		transacted(g) { _ =>
			val courtyard = createRoom("cortile", "Un muro circonda questo piccolo spazio verde, costellato con un paio di alberi e molti cespugli")
			val hall = createRoom("ingresso", "Una stanza confortevole e spaziosa, illuminata da un lampadario dall'aspetto antico e arredata decorosamente")
			val terrace = createRoom("terrazza", "Da questa terrazza e' possibile intravedere in lontananza la linea del mare. Il pavimento e' composto di ceramiche dallo stile antico, ma niente di piu'")

			joinRooms(courtyard, hall, "portone", "una massiccia porta che conduce all'edificio")
			joinRooms(hall, courtyard, "uscita", "la porta verso l'esterno")

			joinRooms(hall, terrace, "scalinata", "una scalinata in ebano lucido")
			joinRooms(terrace, hall, "accesso", "una porta per la scalinata al piano inferiore")
		
			courtyard
		}
	}

	private def transacted[A](g: GraphDatabaseService)(op: GraphDatabaseService => A): Try[A] =  {
		val tx = g.beginTx()
		val result = Try {
			val r = op(g)
			tx.success()
			r
		}
		tx.finish()
		result
	}

	private def nodeDetails(n: Node): (String, String) = (n.getProperty("name").toString, n.getProperty("description").toString)

	private def exitDetails(e: Relationship): (ExitId, String) = (e.getProperty("id").toString, e.getProperty("description").toString)

	private object GraphSearch {
		import org.neo4j.graphdb._
		import org.neo4j.cypher.javacompat.ExecutionEngine
		import scala.collection.JavaConversions._

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

		private def nextRoom(player: UserName): String = 
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[:LEADS_TO]-(r2)<-[:IS_IN]-(other)
				| return other""".stripMargin

		def self(player: UserName)(implicit engine: ExecutionEngine): Node =
			engine.execute(selfNode(player)).columnAs("p").next.asInstanceOf[Node]

		def roomWith(player: UserName)(implicit engine: ExecutionEngine): Node =
			engine.execute(room(player)).columnAs("r").next.asInstanceOf[Node]

		def sameRoomWith(player: UserName)(implicit engine: ExecutionEngine): List[Node] = 
			engine.execute(sameRoom(player)).columnAs("other").toList

		def nextRoomTo(player: UserName)(implicit engine: ExecutionEngine): List[Node] =
			engine.execute(nextRoom(player)).columnAs("other").toList

		def roomExits(room: Node): List[Relationship] =
			room.getRelationships(Direction.OUTGOING, WorldGraph.LEADS_TO).toList

		def allNodes(implicit engine: ExecutionEngine) =
			engine.execute("start n=node(*) return n").columnAs("n").toList

	}
}

