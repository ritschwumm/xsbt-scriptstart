sbtPlugin		:= true

name			:= "xsbt-scriptstart"

organization	:= "de.djini"

version			:= "0.0.3"

scalaVersion	:= "2.9.1"

//publishArtifact in (Compile, packageBin)	:= false

publishArtifact in (Compile, packageDoc)	:= false

publishArtifact in (Compile, packageSrc)	:= false

scalacOptions	++= Seq("-deprecation", "-unchecked")
