package scala.tools.nsc

import java.net.{URI, URL}

import xsbt.{CallbackGlobal, PickleVirtualDirectory, PickleVirtualFile, PicklerGen}
import xsbti.compile.{EmptyIRStore, IRStore}

import scala.collection.immutable
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.util.ClassPath.ClassPathContext
import scala.tools.nsc.util.{DirectoryClassPath, MergedClassPath}

trait ZincPicklePath {
  self: Global =>

  private[this] var store0: IRStore = EmptyIRStore.getStore()

  /** Returns the active IR store, set by [[setUpIRStore()]] and cleared by [[clearStore()]]. */
  def store: IRStore = store0

  def clearStore(): Unit = {
    this.store0 = EmptyIRStore.getStore()
  }

  private[this] var originalClassPath: ClassPath[AbstractFile] = null
  def setUpIRStore(store: IRStore): Unit = {
    val rootPickleDirs = store.getDependentsIRs.map(PicklerGen.toVirtualDirectory(_))
    // When resident compilation is enabled, make sure `platform.classPath` points to the original classpath
    val context = platform.classPath.context
    val pickleClassPaths = rootPickleDirs.map(new ZincVirtualDirectoryClassPath(_, context)).toList

    // We do this so that when resident compilation is enabled, pipelining works
    if (originalClassPath == null) {
      originalClassPath = platform.classPath
    }

    val newEntries = pickleClassPaths ++ originalClassPath.entries
    val newClassPath = new MergedClassPath(newEntries, context)
    platform.currentClassPath = Some(newClassPath)
  }

  /**
    * Create our own zinc virtual directory classpath so that we can inject
    * the pickle information from them, similar to the way we do this in 2.12.
    *
    * @param dir The pickle virtual directory.
    * @param context The classpath context.
    */
  case class ZincVirtualDirectoryClassPath(
      override val dir: PickleVirtualDirectory,
      override val context: ClassPathContext[AbstractFile]
  ) extends DirectoryClassPath(dir, context) {
    override def asURLs: Seq[URL] = Nil
    override def asClassPathString = dir.path

    override lazy val (packages, classes) = {
      val classBuf = immutable.Vector.newBuilder[ClassRep]
      val packageBuf = immutable.Vector.newBuilder[DirectoryClassPath]
      dir.iterator foreach { f =>
        f match {
          case dir: PickleVirtualDirectory if validPackage(dir.name) =>
            packageBuf += new ZincVirtualDirectoryClassPath(dir, context)
          case file: PickleVirtualFile if validClassFile(file.name) =>
            classBuf += ClassRep(Some(file), None)
          case _ => ()
        }
      }
      (packageBuf.result(), classBuf.result())
    }

    override def toString() = "virtual directory classpath: " + origin.getOrElse("?")
  }
}
