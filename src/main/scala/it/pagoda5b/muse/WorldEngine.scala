package it.pagoda5b.muse

import akka.actor._
import scala.util.Try
import scala.util.Try._

class WorldEngine extends Actor {
	import Player._

	private val STORAGE_DIR = "/tmp/muse-neo4j-store"

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
		import WorldGraph._


		require(storeDir != null)
		private val graph: GraphDatabaseService = (new GraphDatabaseFactory).newEmbeddedDatabase(storeDir)
		private val nodesIdx: Index[Node] = graph.index.forNodes("nodes")
		private val relsIdx: RelationshipIndex = graph.index.forRelationships("rels")
		private val startRoom: Long = populate(graph).map(_.getId).getOrElse(0L)

		def stop(): Unit = graph.shutdown()

		def addPlayer(player: String): Unit = {

		}

	}

	private object WorldGraph {
		import org.neo4j.graphdb._

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

	}
}