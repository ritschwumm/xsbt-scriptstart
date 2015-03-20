package xsbtScriptStart

import sbt._

import Keys.TaskStreams

import xsbtUtil.types._
import xsbtUtil.{ util => xu }

import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
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
	
	//------------------------------------------------------------------------------
	
	val scriptstart				= taskKey[File]("complete build, returns the created directory")
	val scriptstartAppDir		= settingKey[File]("where to put starts scripts, jar files and extra files")
	
	val scriptstartZip			= taskKey[File]("complete build, returns the created application zip file")
	val scriptstartAppZip		= settingKey[File]("where to put the application zip file")
	
	val scriptstartPackageName	= settingKey[String]("name of the package built")
	val scriptstartConfigs		= taskKey[Seq[ScriptConfig]]("one or more startscripts to be generated")
	val scriptstartExtras		= taskKey[Traversable[PathMapping]]("additional resources as a task to allow inclusion of packaged wars etc.")

	val scriptstartBuildDir		= settingKey[File]("base directory of built files")
}

object ScriptStartPlugin extends AutoPlugin {
	//------------------------------------------------------------------------------
	//## constants
	
	private val libName	= "lib"
	
	//------------------------------------------------------------------------------
	//## exports
	
	lazy val autoImport	= Import
	import autoImport._
	
	override val requires:Plugins		= ClasspathPlugin && plugins.JvmPlugin
	
	override val trigger:PluginTrigger	= noTrigger

	override lazy val projectSettings:Seq[Def.Setting[_]]	=
			Vector(
				scriptstart	:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							configs		= scriptstartConfigs.value,
							extras		= scriptstartExtras.value,
							appDir		= scriptstartAppDir.value
						),
				scriptstartAppDir		:= scriptstartBuildDir.value / scriptstartPackageName.value,
				
				scriptstartZip	:=
						zipTask(
							streams		= Keys.streams.value,
							appDir		= scriptstart.value,
							prefix		= scriptstartPackageName.value,
							appZip		= scriptstartAppZip.value
						),
				scriptstartAppZip		:= scriptstartBuildDir.value / (scriptstartPackageName.value + ".zip"),
				
				scriptstartPackageName	:= Keys.name.value + "-" + Keys.version.value,
				scriptstartConfigs		:= Seq.empty,
				scriptstartExtras		:= Seq.empty,
				
				scriptstartBuildDir		:= Keys.crossTarget.value / "scriptstart"
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		configs:Traversable[ScriptConfig],
		extras:Traversable[(File,String)],
		appDir:File
	):File = {
		streams.log info s"building scriptstart app in ${appDir}"
		
		val assetDir	= appDir / libName
		streams.log info s"copying assets to ${assetDir}"
		assetDir.mkdirs()
		val assetsToCopy	= assets map { _.flatPathMapping } map (xu.pathMapping anchorTo assetDir)
		val assetsCopied	= IO copy assetsToCopy
		
		streams.log info s"copying extras to ${appDir}"
		val extrasToCopy	= extras map (xu.pathMapping anchorTo appDir)
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info s"creating scripts in ${appDir}"
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
						var	target	= appDir / (config.scriptName + suffix)
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
		val allFiles	= (xu.find files appDir).toSet
		val obsolete	= allFiles -- scripts -- assetsCopied -- extrasCopied
		IO delete obsolete
		
		appDir
	}
	
	/** build app zip */
	private def zipTask(
		streams:TaskStreams,	
		appDir:File,
		prefix:String,
		appZip:File
	):File = {
		streams.log info s"creating zip file ${appZip}"
		xu.zip create (
			sources		= xu.find filesMapped appDir map (xu.pathMapping prefixPath prefix),
			outputZip	= appZip
		)
		appZip
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
		
		val vmOptions			= data.vmOptions map xu.script.unixHardQuote mkString " "
		val systemProperties	= xu.script systemProperties data.systemProperties map xu.script.unixHardQuote mkString " "
		val baseProperty		= xu.script unixSoftQuote (xu.script systemProperty ("scriptstart.base", "$BASE"))
		val classPath			= data.classPath map { libName + "/" + _ } map xu.script.unixHardQuote map { (xu.script unixSoftQuote "$BASE") + "/" + _ } mkString ":"
		val mainClass			= xu.script unixHardQuote data.mainClass
		val prefixArguments		= data.prefixArguments map xu.script.unixHardQuote mkString " "
		val passArguments		= xu.script unixSoftQuote "$@"
		
		val fullScript	=
				s"""
				|	#!/bin/bash
				|	
				${baseFinder}
				|	
				|	exec java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
				"""
				
		xu.text stripped fullScript
	}
		
	private def windowsStartScript(data:ScriptData):String	= {
		val vmOptions			= data.vmOptions map xu.script.windowsQuote mkString " "
		val systemProperties	= xu.script systemProperties data.systemProperties	map xu.script.windowsQuote mkString " "
		val baseProperty		= xu.script windowsQuote (xu.script systemProperty ("scriptstart.base", "."))
		val classPath			= xu.script windowsQuote (data.classPath map { libName + "\\" + _ } mkString ";")
		val mainClass			= xu.script windowsQuote (data.mainClass)
		val prefixArguments		= data.prefixArguments map xu.script.windowsQuote mkString " "
		val passArguments		= "%*"
		
		val fullScript	=
			s"""
			|	cd /d %~dp0%
			|	java ${vmOptions} ${systemProperties} ${baseProperty} -cp ${classPath} ${mainClass} ${prefixArguments} ${passArguments}
			"""
			
		xu.script windowsLF (xu.text stripped fullScript)
	}
}
