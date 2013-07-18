package it.pagoda5b.muse

import akka.actor._
import scala.util.Try
import scala.util.Try._
import scala.util.Properties
import Player.UserName
import Phraser._

class WorldEngine extends Actor {
	import Player._

	private val STORAGE_DIR = Properties.tmpDir + "muse-neo4j-store"

	private[this] val world = new WorldGraph(STORAGE_DIR)

	def receive = {
		case _ => 
	}

	override def postStop() {
		world.stop()
	}


	private class WorldGraph(storeDir: String) {
		import org.neo4j.graphdb._
		import org.neo4j.graphdb.index._
		import org.neo4j.graphdb.factory._
 		import org.neo4j.cypher.javacompat.ExecutionEngine
		import WorldGraph._
		import GraphSearch._

		require(storeDir != null)
		private val graph: GraphDatabaseService = (new GraphDatabaseFactory).newEmbeddedDatabase(storeDir)
		private val playersIdx: Index[Node] = graph.index.forNodes("Players")
		//private val relsIdx: RelationshipIndex = graph.index.forRelationships("rels")
		private implicit val queryEngine = new ExecutionEngine(graph)
		private val startRoom: Long = populate(graph).map(_.getId).getOrElse(0L)

		def stop(): Unit = graph.shutdown()

		def addPlayer(player: UserName): UpdateEvents = {

			//Tries to update the world graph
			def added: Try[Node] = transacted(graph) { g =>
				val pl = g.createNode
				pl.setProperty("name", player)
				pl.setProperty("description", "uno sconosciuto")

				playersIdx.add(pl, PLAYER_NAME, player)

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
				val bystandersPhrase = PlayerAdded(nodeDetails(room)._2)
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

	}

	private object WorldGraph {
		import org.neo4j.graphdb._

		val PLAYER_NAME = "name"

		type UpdateEvents = List[(GameEvent, List[UserName])]

		case object IS_IN extends RelationshipType {val name: String = "IS_IN"}
		case object LEADS_TO extends RelationshipType {val name: String = "LEADS_TO"}

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
				op(g)
			}
			tx.finish()
			result
		}

		def nodeDetails(n: Node): (String, String) = (n.getProperty("name").toString, n.getProperty("description").toString)

		def exitDetails(e: Relationship): (ExitId, String) = (e.getProperty("id").toString, e.getProperty("description").toString)



	}

	object GraphSearch {
		import org.neo4j.graphdb._
		import org.neo4j.cypher.javacompat.ExecutionEngine
		import scala.collection.JavaConversions._
		
		private def room(player: UserName): String = 
			s"""start p=node:Player(name="$player") 
				| match (p)-[:IS_IN]->(r)
				| return r""".stripMargin

		private def sameRoom(player: UserName): String = 
			s"""start p=node:Player(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[:IS_IN]-(other)
				| where other <> p
				| return other""".stripMargin

		private def nextRoom(player: UserName): String = 
			s"""start p=node:Player(name="$player") 
				| match (p)-[:IS_IN]->(r)<-[:LEADS_TO]-(r2)<-[:IS_IN]-(other)
				| return other""".stripMargin

		def roomWith(player: UserName)(implicit engine: ExecutionEngine): Node =
			engine.execute(room(player)).columnAs("r").next.asInstanceOf[Node]

		def sameRoomWith(player: UserName)(implicit engine: ExecutionEngine): List[Node] = 
			engine.execute(sameRoom(player)).columnAs("other").toList

		def nextRoomTo(player: UserName)(implicit engine: ExecutionEngine): List[Node] =
			engine.execute(nextRoom(player)).columnAs("other").toList

		def roomExits(room: Node): List[Relationship] =
			room.getRelationships(Direction.OUTGOING, WorldGraph.LEADS_TO).toList

	}
}