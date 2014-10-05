import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._
import xsbtUtil._

object ScriptStartPlugin extends Plugin {
	private val libName	= "lib"
	
	//------------------------------------------------------------------------------
	//## exported
	
	case class ScriptConfig(
		// name of the generated start scripts
		scriptName:String,
		// passed to the VM from the start script
		vmOptions:Seq[String]				= Seq.empty,
		// passed as -D arguments to the VM
		systemProperties:Map[String,String]	= Map.empty,
		// name of the class the script should run
		mainClass:String,
		// arguments given to the main class
		prefixArguments:Seq[String]			= Seq.empty
	)
	
	val scriptstart				= taskKey[File]("complete build, returns the created directory")
	val scriptstartConfigs		= taskKey[Seq[ScriptConfig]]("one or more startscripts to be generated")
	val scriptstartExtras		= taskKey[Traversable[PathMapping]]("additional resources as a task to allow inclusion of packaged wars etc.")
	val scriptstartTargetDir	= settingKey[File]("where to put starts scripts, jar files and extra files")

	lazy val scriptstartSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Vector(
				scriptstart	:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							configs		= scriptstartConfigs.value,
							extras		= scriptstartExtras.value,
							targetDir	= scriptstartTargetDir.value
						),
				scriptstartConfigs		:= Seq.empty,
				scriptstartExtras		:= Seq.empty,
				scriptstartTargetDir	:= Keys.crossTarget.value / "scriptstart"
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		configs:Traversable[ScriptConfig],
		extras:Traversable[(File,String)],
		targetDir:File
	):File = {
		streams.log info s"building scriptstart app in ${targetDir}"
		
		val assetDir	= targetDir / libName
		streams.log info s"copying assets to ${assetDir}"
		assetDir.mkdirs()
		val assetsToCopy	= assets map { _.flatPathMapping } map (PathMapping anchorTo assetDir)
		val assetsCopied	= IO copy assetsToCopy
		
		streams.log info s"copying extras to ${targetDir}"
		val extrasToCopy	= extras map (PathMapping anchorTo targetDir)
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info s"creating scripts in ${targetDir}"
		val scripts	=
				configs flatMap { config =>
					val assetNames	= assets map { _.jar.getName }
					
					val scriptData	= 
							ScriptData(
								vmOptions			= config.vmOptions,
								systemProperties	= config.systemProperties,
								classPath			= assetNames,	
								mainClass			= config.mainClass,
								prefixArguments		= config.prefixArguments
							)
					
					def writeScript(suffix:String, mkScript:ScriptData=>String):File	= {
						val content	= mkScript(scriptData)
						var	target	= targetDir / (config.scriptName + suffix)
						IO write (target, content)
						target
					}
					
					val scripts	= Seq(
						writeScript("",		unixStartScript),
						writeScript(".bat",	windowsStartScript)
					)
					scripts foreach { _ setExecutable (true, false) }
					scripts
				}
		
		streams.log info "cleaning up"
		val allFiles	= filesIn(targetDir).toSet
		val obsolete	= allFiles -- scripts -- assetsCopied -- extrasCopied
		IO delete obsolete
		
		targetDir
	}
	
	//------------------------------------------------------------------------------
	//## script generation
	
	private case class ScriptData(
		vmOptions:Seq[String],
		systemProperties:Map[String,String],
		classPath:Seq[String],
		mainClass:String,
		prefixArguments:Seq[String]
	)
	
	// export LC_CTYPE="en_US.UTF-8"
	private def unixStartScript(data:ScriptData):String	= {
		val baseFinder	=
				"""
				|	# find this script's directory
				|	if which realpath >/dev/null; then
				|		BASE="$(dirname "$(realpath "$0")")"
				|	elif which readlink >/dev/null; then
				|		pushd >/dev/null .
				|		cur="$0"
				|		while [ -n "$cur" ]; do
				|			dir="$(dirname "$cur")"
				|			[ -n "$dir" ] && cd "$dir"
				|			cur="$(readlink "$(basename "$cur")")"
				|		done
				|		BASE="$PWD"
				|		popd >/dev/null
				|	elif which perl >/dev/null; then
				|		BASE="$(dirname "$(echo "$0" | perl -ne 'use Cwd "abs_path";chomp;print abs_path($_) . "\n"')")"
				|	else
				|		BASE="$(dirname "$0")"
				|	fi
				"""
		
		val vmOptions			= data.vmOptions map unixHardQuote mkString " "
		val systemProperties	= scriptSystemPropertyMap(data.systemProperties) map unixHardQuote mkString " "
		val baseProperty		= unixSoftQuote(scriptSystemProperty("scriptstart.base", "$BASE"))
		val classPath			= data.classPath map { libName + "/" + _ } map unixHardQuote map { unixSoftQuote("$BASE") + "/" + _ } mkString ":"
		val mainClass			= unixHardQuote(data.mainClass)
		val prefixArguments		= data.prefixArguments map unixHardQuote mkString " "
		val passArguments		= unixSoftQuote("$@")
		
		val fullScript	=
				s"""
				|	#!/bin/bash
				|	
				${baseFinder}
				|	
				|	exec java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
				"""
				
		fullScript.strippedLines
	}
		
	private def windowsStartScript(data:ScriptData):String	= {
		val vmOptions			= data.vmOptions map windowsQuote mkString " "
		val systemProperties	= scriptSystemPropertyMap(data.systemProperties)	map windowsQuote mkString " "
		val baseProperty		= windowsQuote(scriptSystemProperty("scriptstart.base", "."))
		val classPath			= windowsQuote(data.classPath map { libName + "\\" + _ } mkString ";")
		val mainClass			= windowsQuote(data.mainClass)
		val prefixArguments		= data.prefixArguments map windowsQuote mkString " "
		val passArguments		= "%*"
		
		val fullScript	=
			s"""
			|	cd /d %~dp0%
			|	java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
			"""
			
		windowsLF(fullScript.strippedLines)
	}
}
