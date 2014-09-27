sbtPlugin		:= true

name			:= "xsbt-scriptstart"

organization	:= "de.djini"

version			:= "0.19.0"

addSbtPlugin("de.djini" % "xsbt-classpath" % "0.10.0")

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
