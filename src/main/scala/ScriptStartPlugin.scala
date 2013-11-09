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
		vmArguments:Seq[String]		= Seq.empty,
		// name of the class the script should run
		mainClass:String,
		// arguments given to the main class
		mainArguments:Seq[String]	= Seq.empty
	)
	
	// complete build, returns the created directory
	val scriptstartBuild	= TaskKey[File]("scriptstart")
	// one or more startscripts to be generated
	val scriptstartConfigs	= TaskKey[Seq[ScriptConfig]]("scriptstart-configs")
	// additional resources as a task to allow inclusion of packaged wars etc.
	val scriptstartExtras	= TaskKey[Traversable[(File,String)]]("scriptstart-extras")
	// where to put starts scripts, jar files and extra files
	val scriptstartOutput	= SettingKey[File]("scriptstart-output")

	lazy val scriptstartSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Seq(
				scriptstartBuild	<<= buildTask,
				scriptstartConfigs	:= Seq.empty,
				scriptstartExtras	:= Seq.empty,
				scriptstartOutput	<<= Keys.crossTarget { _ / "scriptstart" }
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private val libName	= "lib"
	
	private def buildTask:Def.Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,
		scriptstartConfigs,
		scriptstartExtras,
		scriptstartOutput
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		configs:Traversable[ScriptConfig],
		extras:Traversable[(File,String)],
		output:File
	):File = {
		streams.log info ("building scriptstart app in " + output)
		
		val assetDir	= output / libName
		streams.log info ("copying assets to " + assetDir)
		assetDir.mkdirs()
		val assetsToCopy	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= assetDir / asset.name
				}
				yield (source, target)
		val assetsCopied	= IO copy assetsToCopy
		
		streams.log info ("copying extras to " + output)
		val extrasToCopy	= extras map { case (file,path) => (file, output / path) }
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info ("creating scripts in " + output)
		val scripts	= configs flatMap { config =>
			val assetNames	= assets map { _.jar.getName }
			
			val scriptData	= ScriptData(config.vmArguments,	assetNames,	config.mainClass, config.mainArguments)
			
			def writeScript(suffix:String, mkScript:ScriptData=>String):File	= {
				val content	= mkScript(scriptData)
				var	target	= output / (config.scriptName + suffix)
				IO write (target, content)
				target
			}
			
			val scripts	= Seq(
				writeScript("",		unixStartScript),
				writeScript(".bat",	windowsStartScript),
				writeScript(".cmd",	os2StartScript)
			)
			scripts foreach { _ setExecutable (true, false) }
			scripts
		}
		
		streams.log info ("cleaning up")
		val allFiles	= (output ** (-DirectoryFilter)).get.toSet
		val obsolete	= allFiles -- scripts -- assetsCopied -- extrasCopied
		IO delete obsolete
		
		output
	}
	
	//------------------------------------------------------------------------------
	//## script generation
	
	private case class ScriptData(
		vmArguments:Seq[String], 
		classPath:Seq[String],
		mainClassName:String,
		mainArguments:Seq[String]
	)
	
	// export LC_CTYPE="en_US.UTF-8"
	private def unixStartScript(data:ScriptData):String	= template(
		Map(
			"vmArguments"	-> (data.vmArguments map unixQuote mkString " "),
			"baseProperty"	-> unixSoftQuote("-Dscriptstart.base=$BASE"),
			"classPath"		-> (data.classPath map { libName + "/" + _ } map unixQuote map { unixSoftQuote("$BASE") + "/" + _ } mkString ":"),
			"mainClassName"	-> unixQuote(data.mainClassName),
			"mainArguments"	-> (data.mainArguments map unixQuote mkString " ")
		),
		strip(
			"""
			|	#!/bin/bash
			|	
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
			|		popd
			|	elif which perl >/dev/null; then
			|		BASE="$(dirname "$(echo "$0" | perl -ne 'use Cwd "abs_path";chomp;print abs_path($_) . "\n"')")"
			|	else
			|		BASE="$(dirname "$0")"
			|	fi
			|	
			|	exec java {{vmArguments}} {{baseProperty}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} "$@"
			"""
		)
	)
		
	// TODO set scriptstart.base
	private def windowsStartScript(data:ScriptData):String	= template(
		Map(
			"vmArguments"	-> (data.vmArguments map windowsQuote mkString " "),
			"baseProperty"	-> windowsQuote("-Dscriptstart.base="),
			"classPath"		-> windowsQuote(data.classPath map { libName + "\\" + _ } mkString ";"),
			"mainClassName"	-> windowsQuote(data.mainClassName),
			"mainArguments"	-> (data.mainArguments	map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	cd /d %~dp0%
			|	java {{vmArguments}} {{baseProperty}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} %*
			"""
		))
	)
	
	// TODO set scriptstart.base
	private def os2StartScript(data:ScriptData):String	= template(
		Map(
			"vmArguments"	-> (data.vmArguments map windowsQuote mkString " "),
			"baseProperty"	-> windowsQuote("-Dscriptstart.base="),
			"classPath"		-> windowsQuote(data.classPath map { libName + "\\" + _ } mkString ";"),
			"mainClassName"	-> windowsQuote(data.mainClassName),
			"mainArguments"	-> (data.mainArguments	map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	java {{vmArguments}} {{baseProperty}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} %*
			"""
		))
	) 
	
	//------------------------------------------------------------------------------
	//## script helper
	
	private val Strip	= """^\s*\|\t(.*)$""".r
	
	private def strip(s:String):String	= 
			lines(s) collect { case Strip(it) => it } mkString "\n"
	
	private def lines(s:String):Seq[String]	=
			(s:scala.collection.immutable.WrappedString).lines.toVector
			
	private def template(args:Iterable[(String,String)], s:String):String	= 
			(args.toList foldLeft s) { case (s,(k,v)) => s replace ("{{"+k+"}}", v) }
			
	private def windowsLF(s:String):String	= 
			s replace ("\n", "\r\n")
			
	private def unixQuote(s:String):String	= 
			"'" + (s replace ("'", "\\'")) + "'"
			
	private def unixSoftQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\\\"")) + "\""
			
	private def windowsQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\"\"")) + "\""
}
