/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package sbt
package internal
package inc

import java.lang.reflect.InvocationTargetException
import java.io.File
import java.net.URLClassLoader
import java.util.Optional

import xsbt.Log
import sbt.util.Logger
import sbt.io.syntax._
import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.internal.util.ManagedLogger
import xsbti.{ AnalysisCallback, Reporter, ReporterUtil, Logger => xLogger }
import xsbti.compile._
import java.nio.file.Paths
import java.net.URL

/**
 * Implement a cached incremental [[ScalaCompiler]] that has been instrumented
 * with the dependency analysis plugin to do incremental compilation.
 *
 * @param scalaInstance The Scala instance to be used for the compiler.
 * @param provider The manager and provider of cached compilers.
 * @param classpathOptions The classpath options for the Scala compiler.
 * @param onArgsHandler Handler that will handle arguments.
 * @param classLoaderCache
 */
final class AnalyzingCompiler(
    // This scala instance refers to the interface
    val scalaInstance: xsbti.compile.ScalaInstance,
    val provider: CompilerBridgeProvider,
    onArgsHandler: Seq[String] => Unit,
    val classLoaderCache: Option[ClassLoaderCache]
) extends CachedCompilerProvider
    with ScalaCompiler {

  def onArgs(f: Seq[String] => Unit): AnalyzingCompiler =
    new AnalyzingCompiler(scalaInstance, provider, f, classLoaderCache)

  def withClassLoaderCache(classLoaderCache: ClassLoaderCache) =
    new AnalyzingCompiler(
      scalaInstance,
      provider,
      onArgsHandler,
      Some(classLoaderCache)
    )

  def apply(
      sources: Array[File],
      changes: DependencyChanges,
      classpath: Array[File],
      singleOutput: File,
      options: Array[String],
      classpathOptions: ClasspathOptions,
      callback: AnalysisCallback,
      maximumErrors: Int,
      cache: GlobalsCache,
      log: ManagedLogger,
      store: IRStore,
      invalidatedClassFiles: Array[File]
  ): Unit = {
    val compArgs = new CompilerArguments(scalaInstance, classpathOptions)
    val arguments = compArgs(Nil, classpath, None, options)
    val output = CompileOutput(singleOutput)
    val basicReporterConfig = ReporterUtil.getDefaultReporterConfig()
    val reporterConfig = basicReporterConfig.withMaximumErrors(maximumErrors)
    val reporter = ReporterManager.getReporter(log, reporterConfig)
    val progress = Optional.empty[CompileProgress]
    compile(
      sources,
      changes,
      arguments.toArray,
      output,
      callback,
      reporter,
      cache,
      log,
      progress,
      store,
      invalidatedClassFiles
    )
  }

  @deprecated("Use the `compile` variant that takes an IR store directly instead.", "1.3.0")
  override def compile(
      sources: Array[File],
      changes: DependencyChanges,
      options: Array[String],
      output: Output,
      callback: AnalysisCallback,
      reporter: Reporter,
      cache: GlobalsCache,
      log: xLogger,
      progressOpt: Optional[CompileProgress]
  ): Unit = {
    compile(
      sources,
      changes,
      options,
      output,
      callback,
      reporter,
      cache,
      log,
      progressOpt,
      EmptyIRStore.getStore(),
      new Array[File](0)
    )
  }

  override def compile(
      sources: Array[File],
      changes: DependencyChanges,
      options: Array[String],
      output: Output,
      callback: AnalysisCallback,
      reporter: Reporter,
      cache: GlobalsCache,
      log: xLogger,
      progressOpt: Optional[CompileProgress],
      store: IRStore,
      invalidatedClassFiles: Array[File]
  ): Unit = {
    val cached = cache(options, output, !changes.isEmpty, this, log, reporter)
    val progress = if (progressOpt.isPresent) progressOpt.get else IgnoreProgress
    compile(sources,
            changes,
            callback,
            log,
            reporter,
            progress,
            store,
            invalidatedClassFiles,
            cached)
  }

  /**
   * @deprecated Use the variant that takes an IR store.
   */
  @Deprecated
  override def compile(
      sources: Array[File],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      log: xLogger,
      reporter: Reporter,
      progress: CompileProgress,
      compiler: CachedCompiler
  ): Unit = {
    compile(sources,
            changes,
            callback,
            log,
            reporter,
            progress,
            EmptyIRStore.getStore(),
            new Array[File](0),
            compiler)
  }

  override def compile(
      sources: Array[File],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      log: xLogger,
      reporter: Reporter,
      progress: CompileProgress,
      store: IRStore,
      invalidatedClassFiles: Array[File],
      compiler: CachedCompiler
  ): Unit = {
    setStoreToUnderlyingCompiler(store, compiler, log)
    onArgsHandler(compiler.commandArguments(sources))
    call("xsbt.CompilerInterface", "run", log)(
      classOf[Array[File]],
      classOf[DependencyChanges],
      classOf[AnalysisCallback],
      classOf[xLogger],
      classOf[Reporter],
      classOf[CompileProgress],
      classOf[Array[File]],
      classOf[CachedCompiler]
    )(sources, changes, callback, log, reporter, progress, invalidatedClassFiles, compiler)
    ()
  }

  override def resetGlobalIRCaches(
      store: IRStore,
      cached: CachedCompiler,
      logger: xLogger
  ): Unit = {
    try {
      call("xsbt.CompilerInterface", "resetGlobalIRCaches", logger)(
        classOf[IRStore],
        classOf[CachedCompiler],
        classOf[xLogger]
      )(store, cached, logger)
    } catch {
      // The compiler doesn't implement it yet (e.g. Dotty's bridge hasn't been updated)
      case _: NoSuchMethodException =>
        Log.debug(
          logger,
          "Missing `resetGlobalIRCaches` entrypoint in compiler interface means the compiler doesn't support pipelining yet."
        )
        ()
    }
    ()
  }

  /** Call the `setIRStore` hook in compiler interface to set the IR store in the compiler. */
  private def setStoreToUnderlyingCompiler(
      store: IRStore,
      cached: CachedCompiler,
      logger: xLogger
  ): Unit = {
    try {
      call("xsbt.CompilerInterface", "setIRStore", logger)(
        classOf[IRStore],
        classOf[CachedCompiler],
        classOf[xLogger]
      )(store, cached, logger)
    } catch {
      // The compiler doesn't implement it yet (e.g. Dotty's bridge hasn't been updated)
      case _: NoSuchMethodException =>
        Log.debug(
          logger,
          "Missing `setIRStore` entrypoint in compiler interface means the compiler doesn't support pipelining yet."
        )
        ()
    }
    ()
  }

  def newCachedCompiler(
      arguments: Array[String],
      output: Output,
      log: xLogger,
      reporter: Reporter
  ): CachedCompiler = newCachedCompiler(arguments: Seq[String], output, log, reporter)

  def newCachedCompiler(
      arguments: Seq[String],
      output: Output,
      log: xLogger,
      reporter: Reporter
  ): CachedCompiler = {
    val compiler = call("xsbt.CompilerInterface", "newCompiler", log)(
      classOf[Array[String]],
      classOf[Output],
      classOf[xLogger],
      classOf[Reporter]
    )(arguments.toArray[String], output, log, reporter)
    compiler.asInstanceOf[CachedCompiler]
  }

  def doc(
      sources: Seq[File],
      classpath: Seq[File],
      outputDirectory: File,
      options: Seq[String],
      classpathOptions: ClasspathOptions,
      maximumErrors: Int,
      log: ManagedLogger
  ): Unit = {
    val reporter = new ManagedLoggedReporter(maximumErrors, log)
    doc(sources, classpath, outputDirectory, options, classpathOptions, log, reporter)
  }

  def doc(
      sources: Seq[File],
      classpath: Seq[File],
      outputDirectory: File,
      options: Seq[String],
      classpathOptions: ClasspathOptions,
      log: Logger,
      reporter: Reporter
  ): Unit = {
    val compArgs = new CompilerArguments(scalaInstance, classpathOptions)
    val arguments =
      compArgs(sources, classpath, Some(outputDirectory), options)
    onArgsHandler(arguments)
    call("xsbt.ScaladocInterface", "run", log)(
      classOf[Array[String]],
      classOf[xLogger],
      classOf[Reporter]
    )(arguments.toArray[String], log, reporter)
    ()
  }

  def console(
      classpath: Seq[File],
      options: Seq[String],
      classpathOptions: ClasspathOptions,
      initialCommands: String,
      cleanupCommands: String,
      log: Logger
  )(loader: Option[ClassLoader] = None, bindings: Seq[(String, Any)] = Nil): Unit = {
    onArgsHandler(consoleCommandArguments(classpath, options, classpathOptions, log))
    val (classpathString, bootClasspath) = consoleClasspaths(classpath, classpathOptions)
    val (names, values) = bindings.unzip
    call("xsbt.ConsoleInterface", "run", log)(
      classOf[Array[String]],
      classOf[String],
      classOf[String],
      classOf[String],
      classOf[String],
      classOf[ClassLoader],
      classOf[Array[String]],
      classOf[Array[Any]],
      classOf[xLogger]
    )(
      options.toArray[String]: Array[String],
      bootClasspath,
      classpathString,
      initialCommands,
      cleanupCommands,
      loader.orNull,
      names.toArray[String],
      values.toArray[Any],
      log
    )
    ()
  }

  private[this] def consoleClasspaths(classpath: Seq[File],
                                      classpathOptions: ClasspathOptions): (String, String) = {
    val arguments = new CompilerArguments(scalaInstance, classpathOptions)
    val classpathString = CompilerArguments.absString(arguments.finishClasspath(classpath))
    val bootClasspath =
      if (classpathOptions.autoBoot) arguments.createBootClasspathFor(classpath) else ""
    (classpathString, bootClasspath)
  }

  def consoleCommandArguments(
      classpath: Seq[File],
      options: Seq[String],
      classpathOptions: ClasspathOptions,
      log: Logger
  ): Seq[String] = {
    val (classpathString, bootClasspath) = consoleClasspaths(classpath, classpathOptions)
    val argsObj = call("xsbt.ConsoleInterface", "commandArguments", log)(
      classOf[Array[String]],
      classOf[String],
      classOf[String],
      classOf[xLogger]
    )(options.toArray[String], bootClasspath, classpathString, log)
    argsObj.asInstanceOf[Array[String]].toSeq
  }

  def force(log: Logger): Unit = { provider.fetchCompiledBridge(scalaInstance, log); () }

  private def call(
      interfaceClassName: String,
      methodName: String,
      log: Logger
  )(argTypes: Class[_]*)(args: AnyRef*): AnyRef = {
    val interfaceClass = getInterfaceClass(interfaceClassName, log)
    val interface = interfaceClass.newInstance.asInstanceOf[AnyRef]
    val method = interfaceClass.getMethod(methodName, argTypes: _*)
    try method.invoke(interface, args: _*)
    catch {
      case e: InvocationTargetException =>
        e.getCause match {
          case c: xsbti.CompileFailed =>
            throw new CompileFailed(c.arguments, c.toString, c.problems)
          case t => throw t
        }
    }
  }

  private[this] def loader(log: Logger) = {
    val interfaceJar = provider.fetchCompiledBridge(scalaInstance, log)
    val interfaceURL = interfaceJar.toURI.toURL
    def createInterfaceLoader =
      new URLClassLoader(
        Array(interfaceURL),
        createDualLoader(scalaInstance.loader(), createUrlClassLoader(interfaceURL))
      )

    classLoaderCache match {
      case Some(cache) =>
        cache.cachedCustomClassloader(
          interfaceJar :: scalaInstance.allJars().toList,
          () => createInterfaceLoader
        )
      case None => createInterfaceLoader
    }
  }

  private def createUrlClassLoader(interface: URL) = {
    this.getClass.getClassLoader match {
      case url: URLClassLoader => url
      case cl
          if cl.getClass.getName == "jdk.internal.loader.ClassLoaders$AppClassLoader" && interface.toString
            .contains("dotty") =>
        // Required with JDK-11
        val classPath = sys.props.getOrElse("java.class.path", "")
        val pathSeparator = sys.props.getOrElse("path.separator", "")
        val allJars = classPath.split(pathSeparator).map(path => Paths.get(path).toUri().toURL())
        new URLClassLoader(allJars, cl)
      case els => els
    }
  }

  private[this] def getInterfaceClass(name: String, log: Logger) =
    Class.forName(name, true, loader(log))

  protected def createDualLoader(
      scalaLoader: ClassLoader,
      sbtLoader: ClassLoader
  ): ClassLoader = {
    val xsbtiFilter = (name: String) => name.startsWith("xsbti.")
    val notXsbtiFilter = (name: String) => !xsbtiFilter(name)
    new classpath.DualLoader(
      scalaLoader,
      notXsbtiFilter,
      _ => true,
      sbtLoader,
      xsbtiFilter,
      _ => false
    )
  }

  override def toString = s"Analyzing compiler (Scala ${scalaInstance.actualVersion})"
}

object AnalyzingCompiler {
  import sbt.io.IO.{ copy, zip, unzip, withTemporaryDirectory }

  /**
   * Compile a Scala bridge from the sources of the compiler as follows:
   *   1. Extract sources from source jars.
   *   2. Compile them with the `xsbti` interfaces on the classpath.
   *   3. Package the compiled classes and generated resources into a JAR.
   *
   * The Scala build depends on some details of this method, please check
   * <a href="https://github.com/jsuereth/scala/commit/3431860048df8d2a381fb85a526097e00154eae0">
   * this link for more information</a>.
   *
   * This method is invoked by build tools to compile the compiler bridge
   * for Scala versions in which they are not present (e.g. every time a new
   * Scala version is installed in your system).
   */
  def compileSources(
      sourceJars: Iterable[File],
      targetJar: File,
      xsbtiJars: Iterable[File],
      id: String,
      compiler: RawCompiler,
      log: Logger
  ): Unit = {
    val isSource = (f: File) => isSourceName(f.getName)
    def keepIfSource(files: Set[File]): Set[File] =
      if (files.exists(isSource)) files else Set()

    /** Generate jar from compilation dirs, the resources and a target name. */
    def generateJar(outputDir: File, dir: File, resources: Seq[File], targetJar: File) = {
      import sbt.io.Path._
      copy(resources.pair(rebase(dir, outputDir)))
      val toBeZipped = outputDir.allPaths.pair(relativeTo(outputDir), errorIfNone = false)
      zip(toBeZipped, targetJar)
    }

    /** Handle the compilation failure of the Scala compiler. */
    def handleCompilationError(compilation: => Unit) = {
      try compilation
      catch {
        case e: xsbti.CompileFailed =>
          val msg = s"Error compiling the sbt component '$id'"
          throw new CompileFailed(e.arguments, msg, e.problems)
      }
    }

    withTemporaryDirectory { dir =>
      // Extract the sources to be compiled
      val extractedSources = (Set[File]() /: sourceJars) { (extracted, sourceJar) =>
        extracted ++ keepIfSource(unzip(sourceJar, dir))
      }.toSeq
      val (sourceFiles, resources) = extractedSources.partition(isSource)
      withTemporaryDirectory { outputDirectory =>
        val scalaVersion = compiler.scalaInstance.actualVersion
        val msg = s"Non-compiled module '$id' for Scala $scalaVersion. Compiling..."
        log.info(msg)
        val start = System.currentTimeMillis
        handleCompilationError {
          val scalaLibraryJar = compiler.scalaInstance.libraryJar
          val restClasspath = xsbtiJars.toSeq ++ sourceJars
          val classpath = scalaLibraryJar +: restClasspath
          compiler(sourceFiles, classpath, outputDirectory, "-nowarn" :: Nil)

          val end = (System.currentTimeMillis - start) / 1000.0
          log.info(s"  Compilation completed in ${end}s.")
        }

        // Create a jar out of the generated class files
        generateJar(outputDirectory, dir, resources, targetJar)
      }
    }
  }

  private def isSourceName(name: String): Boolean =
    name.endsWith(".scala") || name.endsWith(".java")
}

private[this] object IgnoreProgress extends CompileProgress {
  def startUnit(phase: String, unitPath: String): Unit = ()
  def advance(current: Int, total: Int) = true
}
