sbtPlugin		:= true

name			:= "xsbt-scriptstart"

organization	:= "de.djini"

version			:= "0.15.0"

addSbtPlugin("de.djini" % "xsbt-classpath" % "0.8.0")

scalacOptions	++= Seq(
	"-deprecation",
	"-unchecked",
	// "-language:implicitConversions",
	// "-language:existentials",
	// "-language:higherKinds",
	// "-language:reflectiveCalls",
	// "-language:dynamics",
	// "-language:postfixOps",
	// "-language:experimental.macros"
	"-feature"
)
