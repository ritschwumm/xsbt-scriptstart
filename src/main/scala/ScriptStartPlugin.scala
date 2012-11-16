import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

object ScriptStartPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## exported
	
	// complete build, returns the created directory
	val scriptstartBuild			= TaskKey[File]("scriptstart")
	// generated start scripts
	val scriptstartScripts			= TaskKey[Seq[File]]("scriptstart-scripts")
	// where to put starts scripts, jar files and extra files
	val scriptstartOutputDirectory	= SettingKey[File]("scriptstart-output-directory")
	// where to get resources that should be put in the build
	val scriptstartResources		= SettingKey[PathFinder]("scriptstart-resources")
	// additional resources as a task to allow inclusion of packaged wars etc.
	val scriptstartExtraFiles		= TaskKey[Seq[File]]("scriptstart-extra-files")
	
	case class ScriptConf(
		// name of the generated start scripts
		scriptName:String,
		// name of the class the script should run
		mainClass:String,
		// passed to the VM from the start script
		vmArguments:Seq[String]		= Seq.empty,
		// files local to the generated directory supplied to the main class as absolute paths
		// before other scriptstartMainArguments
		mainFiles:Seq[String]		= Seq.empty,
		// arguments given to the main class
		mainArguments:Seq[String]	= Seq.empty
	)
	
	// one or more startscripts to be generated
	val scriptstartScriptConf	= TaskKey[Seq[ScriptConf]]("scriptstart-script-conf")

	lazy val scriptstartSettings	= classpathSettings ++ Seq(
		scriptstartBuild			<<= buildTask,
		scriptstartScripts			<<= scriptsTask,
		scriptstartOutputDirectory	<<= (Keys.crossTarget)					{ _ / "scriptstart"	},
		scriptstartResources		<<= (Keys.sourceDirectory in Runtime)	{ _ / "scriptstart"	},
		scriptstartExtraFiles		:= Seq.empty,
		scriptstartScriptConf		:= Seq.empty
	)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,	
		scriptstartScripts,
		scriptstartResources,				
		scriptstartExtraFiles,
		scriptstartOutputDirectory
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[Asset],
		scripts:Seq[File],
		scriptstartResources:PathFinder,
		extraFiles:Seq[File],
		outputDirectory:File
	):File = {
		streams.log info ("copying assets")
		val assetsToCopy	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= outputDirectory / asset.name
				}
				yield (source, target)
		val assetsCopied	= IO copy assetsToCopy
		
		// TODO check
		// Keys.defaultExcludes
		streams.log info ("copying resources")
		val resourcesToCopy	=
				for {
					dir		<- scriptstartResources.get
					source	<- dir.***.get
					target	= Path rebase (dir, outputDirectory) apply source get
				}
				yield (source, target)
		val resourcesCopied	= IO copy resourcesToCopy
		
		streams.log info ("copying extra files")
		val extrasToCopy	= extraFiles map { it => (it, outputDirectory / it.getName) }
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info ("cleaning up")
		val allFiles	= (outputDirectory * "*").get.toSet
		val obsolete	= allFiles -- scripts -- assetsCopied -- resourcesCopied -- extrasCopied
		IO delete obsolete
		
		outputDirectory
	}
	
	//------------------------------------------------------------------------------
	//## start scripts
	
	private def scriptsTask:Initialize[Task[Seq[File]]]	= (
		Keys.streams,
		classpathAssets,
		scriptstartScriptConf,
		scriptstartOutputDirectory
	) map scriptsTaskImpl
		
	private def scriptsTaskImpl(
		streams:TaskStreams,
		assets:Seq[Asset],
		scriptConf:Seq[ScriptConf],		
		outputDirectory:File
	):Seq[File]	= {
		streams.log info ("creating startscripts")
		scriptConf flatMap { conf =>
			val assetNames	= assets map { _.jar.getName }
			
			def writeScript(suffix:String, content:String):File = {
				var	target	= outputDirectory / (conf.scriptName + suffix)
				IO write (target, content)
				target
			}
			
			val	unixScript		= writeScript("",		unixStartScript(	conf.vmArguments,	assetNames,	conf.mainClass, conf.mainFiles, conf.mainArguments))
			val windowsScript	= writeScript(".bat",	windowsStartScript(	conf.vmArguments,	assetNames,	conf.mainClass, conf.mainFiles, conf.mainArguments))
			val os2Script		= writeScript(".cmd",	os2StartScript(		conf.vmArguments,	assetNames,	conf.mainClass, conf.mainFiles, conf.mainArguments))
			
			val scripts	= Seq(unixScript,windowsScript,os2Script)
			scripts foreach { _ setExecutable (true, false) }
			Seq(unixScript, windowsScript, os2Script)
		}
	}
	
	private def unixStartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainFiles:Seq[String], mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map unixQuote mkString " "),
			"classPath"		-> (classPath map unixQuote map { unixSoftQuote("$BASE") + "/" + _ } mkString ":"),
			"mainClassName"	-> unixQuote(mainClassName),
			"mainFiles"		-> (mainFiles map unixQuote map { unixSoftQuote("$BASE") + "/" + _ } mkString " "),
			"mainArguments"	-> (mainArguments map unixQuote mkString " ")
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
			|	exec java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} {{mainFiles}} "$@"
			"""
		)
	)
		
	// TODO use a base directory like in unixStartScript
	private def windowsStartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainFiles:Seq[String], mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map windowsQuote mkString " "),
			"classPath"		-> windowsQuote(classPath mkString ";"),
			"mainClassName"	-> windowsQuote(mainClassName),
			"mainFiles"		-> (mainFiles		map windowsQuote mkString " "),
			"mainArguments"	-> (mainArguments	map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	cd /d %~dp0%
			|	cd ..
			|	java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainFiles}} {{mainArguments}} %*
			"""
		))
	)
	
	// TODO use a base directory like in unixStartScript
	private def os2StartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainFiles:Seq[String], mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map windowsQuote mkString " "),
			"classPath"		-> windowsQuote(classPath mkString ";"),
			"mainClassName"	-> windowsQuote(mainClassName),
			"mainFiles"		-> (mainFiles		map windowsQuote mkString " "),
			"mainArguments"	-> (mainArguments	map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	cd ..
			|	java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainFiles}} {{mainArguments}} %*
			"""
		))
	) 
	
	private val Strip	= """^\s*\|\t(.*)$""".r
	
	private def strip(s:String):String	= 
			lines(s) collect { case Strip(it) => it } mkString "\n"
	
	private def lines(s:String):Iterator[String]	=
			(s:scala.collection.immutable.WrappedString).lines
			
	private def template(args:Iterable[Pair[String,String]], s:String):String	= 
			args.toList.foldLeft(s) { case (s,(k,v)) => s replace ("{{"+k+"}}", v) }
			
	private def windowsLF(s:String):String	= 
			s replace ("\n", "\r\n")
			
	private def unixQuote(s:String):String	= 
			"'" + (s replace ("'", "\\'")) + "'"
			
	private def unixSoftQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\\\"")) + "\""
			
	private def windowsQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\"\"")) + "\""
}
