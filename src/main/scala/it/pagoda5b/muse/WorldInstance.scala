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
    import com.typesafe.config._
    import scala.sys.props

    private def locale = props.getOrElse("it.pagoda5b.muse.locale", "it")
    private lazy val conf = ConfigFactory.load("worlds/simple-test").getConfig(locale)

    def read(param: String) = conf.getString(param)

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
        room.setProperty("name", read(name))
        room.setProperty("description", read(desc))
        room
      }

      def joinRooms(r1: Node, r2: Node, direct: (String, String), reverse: (String, String)): (Relationship, Relationship) = {
        val (directName, directDesc) = direct
        val (reverseName, reverseDesc) = reverse

        val to = r1.createRelationshipTo(r2, LEADS_TO)
        to.setProperty("name", read(directName))
        to.setProperty("description", read(directDesc))
        val from = r2.createRelationshipTo(r1, LEADS_TO)
        from.setProperty("name", read(reverseName))
        from.setProperty("description", read(reverseDesc))

        (to, from)
      }

      val cleaning = transacted(g) { _ =>
        clearGraph()
      }

      val startRoom = transacted(g) { _ =>
        val courtyard = createRoom("room1-name", "room1-desc")
        val hall = createRoom("room2-name", "room2-desc")
        val terrace = createRoom("room3-name", "room3-desc")

        joinRooms(courtyard, hall, ("r1r2-name" -> "r1r2-desc"), ("r2r1-name" -> "r2r1-desc"))
        joinRooms(hall, terrace, ("r2r3-name" -> "r2r3-desc"), ("r3r2-name" -> "r3r2-desc"))
      
        courtyard
      }

      for {
        _ <- cleaning
        start <- startRoom
      } yield start

    }
  }

}