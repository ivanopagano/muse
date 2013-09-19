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

/**
 * this is the actor responsible for interpreting commands and interact
 * with the virtual environment
 * Based on the results it decides to send a response to the player ui
 */
class WorldEngine extends Actor {
	import WorldEngine._
	import Player._

  //an actor dedicated to delivering a feedback text to the user
	private val responseActor = context.actorOf(Props[ResponseDeliveryActor], "responder")
	//this akka dispatcher, defined in the config file, is dedicated to interaction with the graph db 
	implicit val graphExecutor = context.system.dispatchers.lookup("graph-access-dispatcher")
 
  //tries to instantiate the graph db describing the environment
	private val worldInitCheck: Try[WorldGraph] = WorldGraph()
	//on demand value of the initiated world graph
	private lazy val world = worldInitCheck.get

	worldInitCheck match {
		//preemptive check that the world creation has gone well, otherwise we start the shutdown sequence and print the error on the console
		case Failure(e) => context.actorFor("/user/shutdown") ! MuseServer.ShutDownSequence(s"Failed to create world instance: ${e.getMessage}")
		case Success(_) => println("world is up and turning")
	}

	def receive = {
		case AddPlayer(player) =>
		  //new player, add her in the world and pipe the resulting responses to the dedicated actor
			val updates = world.addPlayer(player)
			//note: the piped updates are all Future objects, this is happening asynchronously
			pipe(updates) to responseActor
		case RemovePlayer(player) =>
		  //player left, remove her from the world
			world.removePlayer(player)
		case DescribeMe(player, desc) =>
		  //the player changed his description, change it on the world and pipe the reponse to the dedicated actor
			val event = world.changeDescription(player, desc)
			val update = event.map(eventFor(_, player))
			pipe(update) to responseActor
		case LookAround(player) =>
		  //the requests a description of his surroundings, get it and pipe it back to the dedicated actor
			val desc = world.getRoomDescription(player)
			val update = desc.map(eventFor(_, player))
			pipe(update) to responseActor
		case GoToExit(player, exit) =>
		  //player changed room, move her in the world and notify the other bystanding players
			val updates = world.goTo(player, exit)
			pipe(updates) to responseActor
			updates onSuccess { case _ =>
				//after the move completes send the player a description of his new whereabouts
				self ! LookAround(player)
			}
		case Perform(player, action) =>
		  //player did something, prepare the feedback to her and other bystanding players and send them to the dedicated actor
			val updates = world.perform(player, action)
			pipe(updates) to responseActor
		case Report =>
		  //print a debug report of the world state on the console
			world.report
		case _ => 
			//default case, unexpected message type
			unhandled()
	}

	override def postStop() {
		//if this actor stops and the world was correctly initiated, stop the graph database
		worldInitCheck.foreach(_.stop())
	}

}

//specialized actor to deliver feedback updates to the user interface
class ResponseDeliveryActor extends Actor {
	import WorldEngine.UpdateEvents
	import Player._
	import scala.concurrent.ExecutionContext.Implicits._
	
	//creates an actor instance dedicated to translation of the responses to a text meaningful to the user
	private val phraser = context.actorOf(Props[PhraserActor].withRouter(FromConfig()), "phraser")
	//hold a reference to the actor responsible for player communication
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
		  //fallback case, message unknown
			unhandled()
	}

  //prepares a text to be delivered to the selected users, based on the event type
	private def deliver(event: GameEvent, users: List[UserName]): Unit = {
			//a future response with the phrase converted to NL text
			val response = for {
				/*
				 * ask the phraser to prepare the text output and create a Future 
				 * containing the list of players involved, paired with the same text
				 */
				text <- (phraser ? event).mapTo[String]
			} yield PlayerUpdates(users zip Stream.continually(text))

      //pipe the future response to the player actor
			pipe(response) to playerActor
	}

}

//module to hold some useful tooling 
object WorldEngine {
	//alias for a complex response type, it's a list of events to be delivered to possibly many players each
	type UpdateEvents = List[(GameEvent, List[UserName])]

	//utility to create an update event for a single player with less typing
	def eventFor(event: GameEvent, player: UserName) = (event, List(player))

	//empty list of ui updates as a fallback to failure cases
	val noUpdates: Future[UpdateEvents] = Future.successful[UpdateEvents](Nil)
}

/*
 * this class contains the actual graph representing the players environment and interacts with it
 */
private[muse] class WorldGraph(graph: GraphDatabaseService) {
	import org.neo4j.graphdb._
	import org.neo4j.graphdb.index._
	import org.neo4j.cypher.javacompat.ExecutionEngine
	import WorldGraph._
	import GraphSearch._
	import WorldEngine._

  //prepare an index for player nodes (old-style indexing used in neo4j versions prior to 2.0)
	private val playersIdx: Index[Node] = graph.index.forNodes("Players")
	//engine used to execute graph cypher queries, implicitly available
	private implicit val queryEngine = new ExecutionEngine(graph)

  /*
   * creates an instance of the Test World on the supplied
   * graph db and gets back the id of the node representing
   * the starting room for all new players
   */
	private val startRoom: Long = WorldInstances.SimpleTestWorld.populate(graph).get.getId

  /*
   *call this to stop the graph db engine
   */
	def stop(): Unit = graph.shutdown()

  /*
   * connect a player node to the room node he'll be in
   */
	def putIn(player: Node, room: Node): Unit = {
		if (player.hasRelationship) {
			//remove pending location
			player.getSingleRelationship(IS_IN, Direction.OUTGOING).delete()
		}
		//update location
		player.createRelationshipTo(room, IS_IN)
	}

  /*
   * a new player must be added to the game environment
   */
	def addPlayer(player: UserName)(implicit executor: ExecutionContext): Future[UpdateEvents] = {

		//Local method to try and update the world graph, every graph update is wrapped in a transaction that can fail
		def added: Try[Node] = transacted(graph) { g =>

			//create a node representing the player with default properties and a user defined name
			val pl = g.createNode
			pl.setProperty("name", player)
			pl.setProperty("description", Localizer.defaultPlayerDescription)

      //index the node by name, to find it with graph queries
			playersIdx.add(pl, "name", player)

      //put the player in the starting room
			putIn(pl, g.getNodeById(startRoom))
      
      //return the new node
			pl
		}

		//local method to prepare feedback messages for the involved players
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

		//map on the eventual success result
		val feedbacks = added.map{updates(_)}
		//no feedback if there's been an error		
		feedbacks.getOrElse(noUpdates)
	}

  /*
   * a player must be removed from the game environment
   */
	def removePlayer(player: UserName)(implicit executor: ExecutionContext): Unit = 
		self(player) foreach { pl => 
			//found the player node from the name
			transacted(graph) { g =>
				import scala.collection.JavaConversions._
				
				//remove the index, any pending relationship and eventually the node itself
				playersIdx.remove(pl)
				pl.getRelationships(Direction.OUTGOING) foreach {_.delete()}
				pl.delete()
			}
		}

  /*
   * a player has a new description
   */
	def changeDescription(player: UserName, description: String)(implicit executor: ExecutionContext): Future[GameEvent] =
		for {
			//find the player node by name
			pl <- self(player)
		} yield {
			//if found, change the node description property and return a feedback event
			val update = transacted(graph) { g =>
				pl.setProperty("description", description)
				PlayerDescribed
			}
			//if the update fails, return a no-op event
			update.getOrElse(NoOp)
		}

  /*
   * the player wants to know what's in his location
   */
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

  /*
   * A player made some generic action, described as free text.
   * The player itself is supposed to receive a message describing herself doing what he wrote.
   * Others in the same location will see the action following the player's description.
   * Players in the nearby location (1 exit away) will receive a generic message telling that something's happening
   * in the nearby location.
   */
	def perform(player: UserName, action: String)(implicit executor: ExecutionContext): Future[UpdateEvents] = {
		//local method that extracts player's names from a list of relationships with the corresponding nodes
		def collapseNeighbours(l: List[(Relationship, Node)]): List[UserName] =
			l map {
				//for each pair in the list convert to the corresponding node's first property (i.e. the node's name)
				case (r, n) => nodeProperties(n)._1
			}

		for {
			//get the acting player
			actor <- self(player)
			//find players in the same room
			sameRoom <- sameRoomWith(player)
			//find players in the nearby rooms
			nextRoom <- nextDoorsTo(player)	
			//describe action for actor (i.e. the acting player)
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
		} yield actorPhrase :: sameRoomPhrase :: nextRoomPhrase //concatenates the response events

	}

  /*
   * a player has to be moved through the selected exit
   */
	def goTo(player: UserName, name: ExitName)(implicit executor: ExecutionContext): Future[UpdateEvents] = {
    //local method to prepare the response events for all players involved
		def cross(exit: Option[Relationship]): Future[UpdateEvents] = exit match {
			case None => 
				//wrong exit name?
				Future {
					//single event notifying a non-existing exit
					eventFor(NoExit(name), player) :: Nil
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
					nextRoom <- nextDoorsToThrough(player, name)
					//extract readable properties
					playerDesc = nodeProperties(pl)._2
					exit = exitProperties(exitEdge)
					bystanders = sameRoom map {
						nodeProperties
					}
					//optional people in the next room
					neighbours = nextRoom map {
						case (entrance, people) =>
						(exitProperties(entrance), people.map(nodeProperties))
					}
					/*
					 * prepare the response events:
					 * - the moving player will see a message of his move
					 * - players in his location will see her leaving
					 * - players in the target location will see her coming
					 */
					playerPhrase = eventFor(PlayerMoving(exit), player)
					leavingPhrase = (PlayerLeaving(playerDesc, exit), bystanders.map(_._1))
					//this value is an option, it depends on whether there's anyone on the destination room
					comingPhrase = neighbours map {
						case (entrance, people) =>
							(PlayerIncoming(playerDesc, entrance), people.map(_._1))
					}
				} yield playerPhrase :: leavingPhrase :: comingPhrase.toList

		}
    
    //local method (in a dedicated transaction) that updates the location in the graph 
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
					//check for an exit with this name
					e <- exitFor(player, name)
					//extract the response events
					updates <- cross(e)
					_ <- Future {
						//if the exit was correct, update the graph concurrently
						e foreach { updateGraph }
					}
				} yield updates

			}
    //return the future responses or else an empty event
		move.getOrElse(noUpdates)
	}
 
  /*
   * console print that shows the current state of the 
   * underlying graph and relevant nodes
   */
	def report(implicit executor: ExecutionContext): Unit = {
		/****DEBUG SESSION****/
		val (ns, rs) = (allNodes, allRelations)
		//print the nodes, if available
		ns onSuccess {
			case n: List[Node] => 
				val nodes = n.map(node => (node.getId -> nodeProperties(node)))

				println(nodes.mkString("NODES [\n", "\n", "\n]"))
		}
		//print the relationships, if available, grouped depending on its role (e.g. connecting players to rooms, rooms to each other)
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

//module to hold useful constants and methods
private[muse] object WorldGraph {
	import org.neo4j.graphdb.factory._
	import com.typesafe.config._

  //possible relationships between nodes
	case object IS_IN extends RelationshipType {val name: String = "IS_IN"}
	case object LEADS_TO extends RelationshipType {val name: String = "LEADS_TO"}

	//companion constructor, it can fail to start the graph engine
	def apply(): Try[WorldGraph] = {
		//read data path from configuration
		val conf = ConfigFactory.load().getConfig("world-engine")

		val storageDir = Properties.tmpDir + conf.getString("graph-dir")

		//create the embedded neo4j graph 
		val graph: GraphDatabaseService = (new GraphDatabaseFactory).newEmbeddedDatabase(storageDir)
		/*
		 * try to create the wrapper class. 
		 * A possible cause of failure could be an incorrect definition of the world's graph
		 */ 
		Try(new WorldGraph(graph)).recoverWith {
			case error =>
			  //if something didn't work, stop it and report the failure
				graph.shutdown()
				Failure(error)
			}
	}

  //utility method to load a graph engine transaction, mandatory for graph updates (and even for reads starting from v2.0-M4) 
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

  //maps the generic graph node to a couple of properties ("name", "description")
	private def nodeProperties(n: Node): (String, String) = (n.getProperty("name").toString, n.getProperty("description").toString)

  //maps the generic graph relationship to a couple of properties ("name", "description")
	private def exitProperties(e: Relationship): (ExitName, String) = (e.getProperty("name").toString, e.getProperty("description").toString)

  //inner object whose responsibility is to define and execute the needed queries on the graph db
	private object GraphSearch {
		import org.neo4j.graphdb._
		import org.neo4j.cypher.javacompat.ExecutionEngine
		import scala.collection.JavaConversions._
		import scala.concurrent.Future

    /*
     * These are the queries written in cypher query language
     */
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

		private def exitWithNameFor(player: UserName, name: ExitName): String =
			s"""start p=node:Players(name="$player") 
				| match (p)-[:IS_IN]->(r)-[exit:LEADS_TO]->(r2)
				| where exit.name = "$name"
				| return exit""".stripMargin

		//actually used for testing purposes
		def allNodes(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute("start n=node(*) return n").columnAs("n").toList
		}

		//actually used for testing purposes
		def allRelations(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Relationship]] = Future {
			engine.execute("start n=node(*) match (n)-[r]-(n2) return distinct r").columnAs("r").toList
		}

    //selects the player node by name
		def self(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Node] = Future {
			engine.execute(selfNode(player)).columnAs("p").next.asInstanceOf[Node]
		}

    //selects the node representing the plaer's current location
		def roomWith(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Node] = Future {
			engine.execute(room(player)).columnAs("r").next.asInstanceOf[Node]
		}

    //selects the nodes representing other players in the same location, excluding the current player
		def sameRoomWith(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute(sameRoom(player)).columnAs("other").toList
		}

    //selects the nodes representing other players in the rooms next to the one where the current player is located
		def nextRoomsTo(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[Node]] = Future {
			engine.execute(nextRooms(player)).columnAs("other").toList
		}

    //selects both players in the room next to the current player location and the exit leading to such location, paired together
		def nextDoorsTo(player: UserName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[List[(Relationship, Node)]] = Future {
			val rows = engine.execute(nextDoors(player))
			(rows map (r => (r("exit").asInstanceOf[Relationship], r("other").asInstanceOf[Node]))).toList
		}

		//similar to nextDoorsTo but only selects the adjacent room reached by the specified exit. The result is None, if no player is in the nearby location.
		def nextDoorsToThrough(player: UserName, id: ExitName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Option[(Relationship, List[Node])]] = Future {

			val rows = engine.execute(nextDoorsThrough(player, id))
			val neighbour = (rows map (r => (r("entrance").asInstanceOf[Relationship], r("other").asInstanceOf[Node]))).toList
			neighbour match {
				case Nil => None
				case list => 
				  //transforms the list of many players with the same entrance name to a single pair of the entrance name and the list of players
					val (entrances, people) = list.unzip
					Some((entrances.head, people))
			}
			
		}

    //selects a possible relationships representing the exit with the specified name, in the current player location
		def exitFor(player: UserName, name: ExitName)(implicit engine: ExecutionEngine, executor: ExecutionContext): Future[Option[Relationship]] = Future {
			engine.execute(exitWithNameFor(player, name)).columnAs("exit").toList.headOption
		}

    //finds outgoing relationships from the specified room noe leading to other room nodes (i.e. a list of exits)
		def roomExits(room: Node): List[Relationship] =
			room.getRelationships(Direction.OUTGOING, WorldGraph.LEADS_TO).toList

    //finds the target node (destination room) of an exit represented by the selected relationship
		def exitDestination(exit: Relationship): Node =
			exit.getEndNode


	}
}

