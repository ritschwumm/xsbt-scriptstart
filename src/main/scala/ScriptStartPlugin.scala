import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

object ScriptStartPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## exported
	
	val scriptstartBuild			= TaskKey[File]("scriptstart")
	val scriptstartAssets			= TaskKey[Seq[Asset]]("scriptstart-assets")
	val scriptstartScripts			= TaskKey[Seq[File]]("scriptstart-scripts")
	val scriptstartOutputDirectory	= SettingKey[File]("scriptstart-output-directory")
	val scriptstartResources		= SettingKey[PathFinder]("scriptstart-resources")
	val scriptstartScriptName		= SettingKey[String]("scriptstart-script-name")
	val scriptstartVmArguments		= TaskKey[Seq[String]]("scriptstart-vm-arguments")
	val scriptstartMainArguments	= TaskKey[Seq[String]]("scriptstart-main-arguments")
	val scriptstartMainClass		= SettingKey[String]("scriptstart-main-class")
	val scriptstartExtraFiles		= TaskKey[Seq[File]]("scriptstart-extra-files")

	lazy val allSettings	= Seq(
		scriptstartBuild			<<= buildTask,
		scriptstartAssets			<<= assetsTask,
		scriptstartScripts			<<= scriptsTask,
		scriptstartOutputDirectory	<<= (Keys.crossTarget) { _ / "scriptstart" },
		scriptstartResources		<<= (Keys.sourceDirectory in Runtime) { _ / "webstart" },
		scriptstartScriptName		<<= Keys.name.identity,
		scriptstartVmArguments		:= Seq.empty,
		scriptstartMainArguments	:= Seq.empty,
		scriptstartMainClass		:= null,
		scriptstartExtraFiles		:= Seq.empty
	)
	
	case class Asset(main:Boolean, fresh:Boolean, jar:File) {
		val name:String	= jar.getName
	}
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Initialize[Task[File]] = {
		(Keys.streams,			scriptstartAssets,	scriptstartScripts,	scriptstartResources,				scriptstartExtraFiles,	scriptstartOutputDirectory) map {
		(streams:TaskStreams,	assets:Seq[Asset],	scripts:Seq[File],	scriptstartResources:PathFinder,	extraFiles:Seq[File],	outputDirectory:File) => {
			// TODO check
			// Keys.defaultExcludes
			streams.log info ("copying resources")
			val resourcesToCopy	=
					for {
						dir		<- scriptstartResources.get
						file	<- dir.***.get
						target	= Path.rebase(dir, outputDirectory)(file).get
					}
					yield (file, target)
			val resourcesCopied	= IO copy resourcesToCopy
			
			streams.log info ("copying extra files")
			val extraCopied	= IO copy (extraFiles map { it => (it, outputDirectory / it.getName) })
			
			streams.log info ("cleaning up")
			val allFiles	= (outputDirectory * "*").get.toSet
			val assetJars	= assets map { _.jar }
			val obsolete	= allFiles -- assetJars -- scripts -- resourcesCopied -- extraCopied
			IO delete obsolete
			
			outputDirectory
		}}
	}
	
	//------------------------------------------------------------------------------
	//## jar files
	
	private def assetsTask:Initialize[Task[Seq[Asset]]]	= {
		// BETTER use dependencyClasspath and products instead of fullClasspath?
		// BETTER use exportedProducts instead of products?
		(Keys.streams,			Keys.products in Runtime,	Keys.fullClasspath in Runtime,	Keys.cacheDirectory,	scriptstartOutputDirectory) map {
		(streams:TaskStreams,	products,					fullClasspath,					cacheDirectory:File,	outputDirectory:File) => {
			// NOTE for directories, the package should be named after the artifact they come from
			val (archives, directories)	= fullClasspath.files.distinct partition ClasspathUtilities.isArchive
			
			streams.log info ("creating directory jars")
			val directoryAssets	= directories.zipWithIndex map { case (source, index) =>
				val main	= products contains source
				val cache	= cacheDirectory / scriptstartAssets.key.label / index.toString
				val target	= outputDirectory / (index + ".jar")
				val fresh	= jarDirectory(source, cache, target)
				Asset(main, fresh, target)
			}
			
			streams.log info ("copying library jars")
			val archiveAssets	= archives map { source =>
				val main	= products contains source
				val	target	= outputDirectory / source.getName 
				val fresh	= copyArchive(source, target)
				Asset(main, fresh, target)
			}
			
			val assets	= archiveAssets ++ directoryAssets
			val (freshAssets,unchangedAssets)	= assets partition { _.fresh }
			streams.log info (freshAssets.size + " fresh jars, " + unchangedAssets.size + " unchanged jars")
			
			assets
		}}
	}
	
	private def copyArchive(sourceFile:File, targetFile:File):Boolean	= {
		val fresh	= !targetFile.exists || sourceFile.lastModified > targetFile.lastModified
		if (fresh) {
			IO copyFile (sourceFile, targetFile)
		}
		fresh
	}
	
	private def jarDirectory(sourceDir:File, cacheDir:File, targetFile:File):Boolean	= {
		import Predef.{conforms => _, _}
		import collection.JavaConversions._
		import Types.:+:
		
		import sbinary.{DefaultProtocol,Format}
		import DefaultProtocol.{FileFormat, immutableMapFormat, StringFormat, UnitFormat}
		import Cache.{defaultEquiv, hConsCache, hNilCache, streamFormat, wrapIn}
		import Tracked.{inputChanged, outputChanged}
		import FileInfo.exists
		import FilesInfo.lastModified
		
		implicit def stringMapEquiv: Equiv[Map[File, String]] = defaultEquiv
		
		val sources		= (sourceDir ** -DirectoryFilter get) x (Path relativeTo sourceDir)
		
		def makeJar(sources:Seq[(File, String)], jar:File) {
			IO delete jar
			IO zip (sources, jar)
		}
		
		val cachedMakeJar = inputChanged(cacheDir / "inputs") { (inChanged, inputs:(Map[File, String] :+: FilesInfo[ModifiedFileInfo] :+: HNil)) =>
			val sources :+: _ :+: HNil = inputs
			outputChanged(cacheDir / "output") { (outChanged, jar:PlainFileInfo) =>
				val fresh	= inChanged || outChanged
				if (fresh) {
					makeJar(sources.toSeq, jar.file)
				}
				fresh
			}
		}
		val sourcesMap		= sources.toMap
		val inputs			= sourcesMap :+: lastModified(sourcesMap.keySet.toSet) :+: HNil
		val fresh:Boolean	= cachedMakeJar(inputs)(() => exists(targetFile))
		fresh
	}

	//------------------------------------------------------------------------------
	//## start scripts
	
	private def scriptsTask:Initialize[Task[Seq[File]]] = {
		(Keys.streams,			scriptstartAssets,	scriptstartScriptName,	scriptstartMainClass,	scriptstartVmArguments,		scriptstartMainArguments, 	scriptstartOutputDirectory) map {
		(streams:TaskStreams,	assets:Seq[Asset],	scriptName:String,		mainClass:String,		vmArguments:Seq[String],	mainArguments:Seq[String],	outputDirectory:File) => {
			streams.log info ("creating startscripts")
			
			require(mainClass != null, scriptstartMainClass.key.label + " must be set")
			
			val assetNames	= assets map { _.jar.getName }
			
			def writeScript(suffix:String, content:String):File = {
				var	target	= outputDirectory / (scriptName + suffix)
				IO write (target, content)
				target
			}
			
			val	unixScript		= writeScript("",		unixStartScript(	vmArguments,	assetNames,	mainClass, mainArguments))
			val windowsScript	= writeScript(".bat",	windowsStartScript(	vmArguments,	assetNames,	mainClass, mainArguments))
			val os2Script		= writeScript(".cmd",	os2StartScript(		vmArguments,	assetNames,	mainClass, mainArguments))
			
			Seq(unixScript, windowsScript, os2Script)
		}}
	}
	
	private def unixStartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map unixQuote mkString " "),
			"classPath"		-> unixQuote(classPath mkString ":"),
			"mainClassName"	-> unixQuote(mainClassName),
			"mainArguments"	-> (mainArguments map unixQuote mkString " ")
		),
		strip(
			"""
			|	#!/bin/bash
			|	
			|	# change into this script's directory
			|	if which realpath >/dev/null; then
			|		cd "$(dirname "$(realpath "$0")")"
			|	elif which readlink >/dev/null; then
			|		cur="$0"
			|		while [ -n "$cur" ]; do
			|			dir="$(dirname "$cur")"
			|			[ -n "$dir" ] && cd "$dir"
			|			cur="$(readlink "$(basename "$cur")")"
			|		done
			|	elif which perl >/dev/null; then
			|		cd "$(dirname "$(echo "$0" | perl -ne 'use Cwd "abs_path";chomp;print abs_path($_) . "\n"')")"
			|	else
			|		cd "$(dirname "$0")"
			|	fi
			|	
			|	exec java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} "$@"
			"""
		)
	)
			
	private def windowsStartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map windowsQuote mkString " "),
			"classPath"		-> windowsQuote(classPath mkString ";"),
			"mainClassName"	-> windowsQuote(mainClassName),
			"mainArguments"	-> (mainArguments map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	cd /d %~dp0%
			|	cd ..
			|	java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} %*
			"""
		))
	)
			
	private def os2StartScript(vmArguments:Seq[String], classPath:Seq[String], mainClassName:String, mainArguments:Seq[String]):String	= template(
		Map(
			"vmArguments"	-> (vmArguments map windowsQuote mkString " "),
			"classPath"		-> windowsQuote(classPath mkString ";"),
			"mainClassName"	-> windowsQuote(mainClassName),
			"mainArguments"	-> (mainArguments map windowsQuote mkString " ")
		),
		windowsLF(strip(
			"""
			|	cd ..
			|	java {{vmArguments}} -cp {{classPath}} {{mainClassName}} {{mainArguments}} %*
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
			
	private def windowsQuote(s:String):String	= 
			"\"" + (s replace ("\"", "\"\"")) + "\""
}
