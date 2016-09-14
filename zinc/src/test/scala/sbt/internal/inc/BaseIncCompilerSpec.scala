package sbt.internal.inc

import java.io.File

import sbt.internal.inc.Analysis.NonLocalProduct
import sbt.io.IO
import sbt.util.InterfaceUtil._
import sbt.util.Logger
import xsbti.Maybe
import xsbti.api.{ExternalDependency, NameHash}
import xsbti.compile._

import scala.util.Random

/**
  * Author: Krzysztof Romanowski
  */
class BaseIncCompilerSpec extends BridgeProviderSpecification {

  def mockedCompiler(in: File): ScalaCompiler = {
    val scalaVersion = scala.util.Properties.versionNumberString
    val compilerBridge = getCompilerBridge(in, Logger.Null, scalaVersion)
    val instance = scalaInstance(scalaVersion)
    new AnalyzingCompiler(instance, CompilerInterfaceProvider.constant(compilerBridge), ClasspathOptionsUtil.boot)
  }

  def mockedMiniSetup: MiniSetup = new MiniSetup(
    new Output {},
    new MiniOptions(Array.empty, Array.empty),
    scala.util.Properties.versionNumberString,
    CompileOrder.Mixed,
    /*_nameHashing*/ true,
    Array.empty
  )

  case class TestCompilerClasspathConfig(
    override val classpath: Seq[File],
    override val perClasspathEntryLookup: PerClasspathEntryLookup,
    override val compiler: xsbti.compile.ScalaCompiler,
    override val currentSetup: MiniSetup = mockedMiniSetup,
    override val incOptions: IncOptions = IncOptionsUtil.defaultIncOptions()
  ) extends CompilerClasspathConfig

  protected def mockedConfig(mockedClasspath: Seq[File], mockedLookup: PerClasspathEntryLookup, tempDir: File): TestCompilerClasspathConfig =
    TestCompilerClasspathConfig(mockedClasspath, mockedLookup, mockedCompiler(tempDir))
}

abstract class ClasspathEntry(in: DirectorySetup) {
  def classpathEntry: File

  def analysis: Option[Analysis]

  in.register(this)
}

class VirtualDir(val name: String, val classes: Seq[String], val in: DirectorySetup) extends ClasspathEntry(in) {
  val dir = new File(in.baseDir, name)
  dir.mkdirs()

  val classFiles = classes.map(classFile)
  classFiles.foreach(_.createNewFile())

  override def classpathEntry: File = dir

  override def analysis: Option[Analysis] = None

  def classFile(binaryName: String) = new File(dir, s"$binaryName.class")
}

case class VirtualJar(override val name: String, override val classes: Seq[String], override val in: DirectorySetup)
  extends VirtualDir(name + "_tmp", classes, in) {
  val location = new File(in.baseDir, s"$name.jar")

  IO.zip(classFiles.map(f => f -> f.getName), location)

  override def classpathEntry: File = location

  override def analysis: Option[Analysis] = None
}

case class VirtualProject(name: String, in: DirectorySetup) extends ClasspathEntry(in) {
  private var _analysis: Analysis = Analysis.Empty

  def newSource(v: VirtualSource): Analysis = {
    val nameHashesArray = Array(new NameHash(v.name, Random.nextInt()))
    val nameHashes = APIs.emptyNameHashes.withRegularMembers(nameHashesArray)
    val newAnalysis = Analysis.empty(true)
      .addSource(
        v.sourceFile,
        Seq(APIs.emptyAnalyzedClass.withName(v.name)
          .withApiHash(v.sourceFile.getAbsolutePath.hashCode)
          .withNameHashes(nameHashes)),
        Stamp.hash(v.sourceFile),
        SourceInfos.emptyInfo,
        Seq(NonLocalProduct(v.name, v.name, v.classFile, Stamp.lastModified(v.classFile))),
        Nil, Nil, v.extDependencies, v.binaryDependencies
      )
    val relations = v.extDependencies.foldLeft(newAnalysis.relations) { (relations, analysis) =>
      relations.addUsedName(v.name, analysis.targetBinaryClassName())
    }
    val updatedAnalysis = newAnalysis.copy(relations = relations)
    _analysis ++= updatedAnalysis
    updatedAnalysis
  }

  val baseDir = new File(in.baseDir, name)
  baseDir.mkdirs()

  val src = new File(baseDir, "src")
  src.mkdir()

  val out = new File(baseDir, "out")
  out.mkdir()

  override def classpathEntry: File = out

  override def analysis: Option[Analysis] = Some(_analysis)
}

case class VirtualSource(
  name: String,
  in: VirtualProject,
  extDependencies: Seq[ExternalDependency] = Seq.empty,
  binaryDependencies: Seq[(File, String, Stamp)] = Seq.empty
) {
  val sourceFile = new File(in.src, s"$name.scala")
  val classFile = new File(in.out, s"$name.class")

  sourceFile.createNewFile()
  classFile.createNewFile()

  val analysis = in.newSource(this)
}

class DirectorySetup(val baseDir: File) {
  private var myEntries = Seq.empty[ClasspathEntry]

  def register(entry: ClasspathEntry) = myEntries :+= entry

  def classpath = myEntries.map(_.classpathEntry)

  def analysisMap = myEntries.map(e => e.classpathEntry -> e.analysis).toMap

  def entryLookup = new PerClasspathEntryLookup() {
    override def analysis(classpathEntry: File): Maybe[CompileAnalysis] =
      o2m(analysisMap.get(classpathEntry).flatten)

    override def definesClass(classpathEntry: File): DefinesClass = Locate.definesClass(classpathEntry)
  }
}