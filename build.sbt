sbtPlugin		:= true

name			:= "xsbt-scriptstart"
organization	:= "de.djini"
version			:= "2.5.0"

scalacOptions	++= Seq(
	"-feature",
	"-deprecation",
	"-unchecked",
	"-Xfatal-warnings",
)

conflictManager	:= ConflictManager.strict withOrganization "^(?!(org\\.scala-lang|org\\.scala-js|org\\.scala-sbt)(\\..*)?)$"
addSbtPlugin("de.djini" % "xsbt-util"		% "1.5.0")
addSbtPlugin("de.djini" % "xsbt-classpath"	% "2.7.0")
