import AssemblyKeys._

scalaVersion := "2.10.2"

name := "muse"

version := "1.0-M2"

assemblySettings

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList("META-INF", "CHANGES.txt") => MergeStrategy.discard
    case PathList("META-INF", "LICENSES.txt") => MergeStrategy.rename
    case x => old(x)
  }
}

libraryDependencies ++= Seq(
	"org.mashupbots.socko" %% "socko-webserver" % "0.3.0",
	"org.neo4j" % "neo4j" % "2.0.0-M03"
)
