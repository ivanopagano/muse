scalaVersion := "2.10.2"

name := "muse"

version := "0.1-SNAPSHOT"

libraryDependencies ++= Seq(
	"org.mashupbots.socko" %% "socko-webserver" % "0.3.0",
	"org.neo4j" % "neo4j" % "2.0.0-M03"
)
