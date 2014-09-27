import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

object ScriptStartPlugin extends Plugin {
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
	
	val scriptstart			= taskKey[File]("complete build, returns the created directory")
	val scriptstartConfigs	= taskKey[Seq[ScriptConfig]]("one or more startscripts to be generated")
	val scriptstartExtras	= taskKey[Traversable[(File,String)]]("additional resources as a task to allow inclusion of packaged wars etc.")
	val scriptstartOutput	= settingKey[File]("where to put starts scripts, jar files and extra files")

	lazy val scriptstartSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Vector(
				scriptstart	:=
						buildTaskImpl(
							streams	= Keys.streams.value,
							assets	= classpathAssets.value,
							configs	= scriptstartConfigs.value,
							extras	= scriptstartExtras.value,
							output	= scriptstartOutput.value
						),
				scriptstartConfigs	:= Seq.empty,
				scriptstartExtras	:= Seq.empty,
				scriptstartOutput	:= Keys.crossTarget.value / "scriptstart"
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private val libName	= "lib"
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		configs:Traversable[ScriptConfig],
		extras:Traversable[(File,String)],
		output:File
	):File = {
		streams.log info s"building scriptstart app in ${output}"
		
		val assetDir	= output / libName
		streams.log info s"copying assets to ${assetDir}"
		assetDir.mkdirs()
		val assetsToCopy	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= assetDir / asset.name
				}
				yield (source, target)
		val assetsCopied	= IO copy assetsToCopy
		
		streams.log info s"copying extras to ${output}"
		val extrasToCopy	= extras map { case (file,path) => (file, output / path) }
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info s"creating scripts in ${output}"
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
						var	target	= output / (config.scriptName + suffix)
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
		val allFiles	= (output ** (-DirectoryFilter)).get.toSet
		val obsolete	= allFiles -- scripts -- assetsCopied -- extrasCopied
		IO delete obsolete
		
		output
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
		val systemProperties	= data.systemProperties map (systemProperty.tupled) map unixSoftQuote mkString " "
		val baseProperty		= unixHardQuote(systemProperty("scriptstart.base", "$BASE"))
		val classPath			= data.classPath map { libName + "/" + _ } map unixHardQuote map { unixSoftQuote("$BASE") + "/" + _ } mkString ":"
		val mainClass			= unixHardQuote(data.mainClass)
		val prefixArguments		= data.prefixArguments map unixHardQuote mkString " "
		val passArguments		= unixSoftQuote("$@")
		strip(
			s"""
			|	#!/bin/bash
			|	
			${baseFinder}
			|	
			|	exec java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
			"""
		)
	}
		
	private def windowsStartScript(data:ScriptData):String	= {
		val vmOptions			= data.vmOptions map windowsQuote mkString " "
		val systemProperties	= data.systemProperties map (systemProperty.tupled) map windowsQuote mkString " "
		val baseProperty		= windowsQuote(systemProperty("scriptstart.base", "."))
		val classPath			= windowsQuote(data.classPath map { libName + "\\" + _ } mkString ";")
		val mainClass			= windowsQuote(data.mainClass)
		val prefixArguments		= data.prefixArguments map windowsQuote mkString " "
		val passArguments		= "%*"
		windowsLF(strip(
			s"""
			|	cd /d %~dp0%
			|	java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
			"""
		))
	}
	
	//------------------------------------------------------------------------------
	//## formatting helper
	
	private val Strip	= """^\s*\|\t(.*)$""".r
	
	private def strip(s:String):String	= 
			lines(s) collect { case Strip(it) => it } mkString "\n"
	
	private def lines(s:String):Seq[String]	=
			(s:scala.collection.immutable.WrappedString).lines.toVector
			
	//------------------------------------------------------------------------------
	//## quoting helper
	
	private def windowsLF(s:String):String	= 
			s replace ("\n", "\r\n")
			
	private def unixHardQuote(s:String):String	= 
			"'" + (s replace ("'", "\\'")) + "'"
			
	private def unixSoftQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\\\"")) + "\""
			
	private def windowsQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\"\"")) + "\""
		
	private val systemProperty:(String,String)=>String	=
			(key:String, value:String) => s"-D${key}=${value}"
}
