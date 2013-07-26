scalaVersion := "2.10.2"

name := "muse"

version := "1.0-M1"

libraryDependencies ++= Seq(
	"org.mashupbots.socko" %% "socko-webserver" % "0.3.0",
	"org.neo4j" % "neo4j" % "2.0.0-M03"
)
