package it.pagoda5b.muse

import org.neo4j.graphdb._
import org.neo4j.tooling._
import scala.util.Try
import scala.util.Try._

/**
 * This module contains individual world maps, 
 * which are actually factories to populate the graph.
 */
object WorldInstances {
  import WorldGraph._

  /**
   * common interface for the world-building instances
   */
  trait Instance {
   /**
    * Creates the locations and in-between connections for a virtual world.
    * If all goes well the starting location for new players is returned.
    */
    def populate(g: GraphDatabaseService): Try[Node]
  }

  object SimpleTestWorld extends Instance {
    def populate(g: GraphDatabaseService): Try[Node] = {
      def clearGraph(): Unit = {
        import scala.collection.JavaConversions._

        val graphOps = (GlobalGraphOperations at g)
        val (nodes, rels) = (graphOps.getAllNodes, graphOps.getAllRelationships)
        rels foreach (_.delete)
        nodes foreach (_.delete)
      }

      def createRoom(name: String, desc: String): Node = {
        val room = g.createNode
        room.setProperty("name", name)
        room.setProperty("description", desc)
        room
      }

      def joinRooms(r1: Node, r2: Node, direct: (String, String), reverse: (String, String)): (Relationship, Relationship) = {
        val to = r1.createRelationshipTo(r2, LEADS_TO)
        to.setProperty("name", direct._1)
        to.setProperty("description", direct._2)
        val from = r2.createRelationshipTo(r1, LEADS_TO)
        from.setProperty("name", reverse._1)
        from.setProperty("description", reverse._2)

        (to, from)
      }

      transacted(g) { _ =>
        clearGraph()
        val courtyard = createRoom("cortile", "Un muro circonda questo piccolo spazio verde, costellato da un paio di alberi e molti cespugli")
        val hall = createRoom("ingresso", "Una stanza confortevole e spaziosa, illuminata da un lampadario dall'aspetto antico e arredata decorosamente")
        val terrace = createRoom("terrazza", "Da questa terrazza e' possibile intravedere in lontananza la linea del mare. Il pavimento e' composto di ceramiche dallo stile antico, ma niente di piu'")

        joinRooms(courtyard, hall, ("il portone", "una massiccia porta che conduce all'edificio"), ("l'uscita", "la porta verso l'esterno"))
        joinRooms(hall, terrace, ("la scalinata", "una scalinata in ebano lucido"), ("l'accesso", "una porta per la scalinata al piano inferiore"))
      
        courtyard
      }
    }
  }

}