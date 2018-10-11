package scala.tools.nsc

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

import xsbt.{ PickleVirtualDirectory, PickleVirtualFile, PicklerGen }
import xsbti.compile.{ EmptyIRStore, IR, IRStore }

import scala.collection.immutable
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.util.ClassPath.ClassPathContext
import scala.tools.nsc.util.{ DirectoryClassPath, MergedClassPath }

trait ZincPicklePath {
  self: Global =>

  private[this] var store0: IRStore = EmptyIRStore.getStore()

  /** Returns the active IR store, set by [[setUpIRStore()]] and cleared by [[clearStore()]]. */
  def store: IRStore = store0

  def clearStore(store: IRStore): Unit = {
    this.store0 = EmptyIRStore.getStore()
    store.getDependentsIRs.foreach { irs =>
      val pickleDir = PicklerGen.removeCacheForIRs(irs)
      // If null it means the IRs were created by different Scala minor version (2.12.1 vs 2.12.4)
      if (pickleDir != null) classpathCache.remove(pickleDir)
    }
  }

  private val classpathCache =
    new ConcurrentHashMap[PickleVirtualDirectory, ZincVirtualDirectoryClassPath]()

  private[this] var originalClassPath: ClassPath[AbstractFile] = null
  def setUpIRStore(store: IRStore): Unit = {
    val rootPickleDirs = store.getDependentsIRs.map(PicklerGen.toVirtualDirectory(_))
    // When resident compilation is enabled, make sure `platform.classPath` points to the original classpath
    val context = platform.classPath.context
    val pickleClassPaths = rootPickleDirs.map { pickleDir =>
      classpathCache.computeIfAbsent(
        pickleDir,
        new java.util.function.Function[PickleVirtualDirectory, ZincVirtualDirectoryClassPath] {
          override def apply(t: PickleVirtualDirectory): ZincVirtualDirectoryClassPath =
            new ZincVirtualDirectoryClassPath(t, context)
        }
      )
    }.toList

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
      dir.children.valuesIterator.foreach { f =>
        f match {
          case dir: PickleVirtualDirectory =>
            val name = dir.name
            if ((name != "") && (name.charAt(0) != '.'))
              packageBuf += new ZincVirtualDirectoryClassPath(dir, context)
          case file: PickleVirtualFile =>
            classBuf += ClassRep(Some(file), None)
          case _ => ()
        }
      }
      (packageBuf.result(), classBuf.result())
    }

    override def toString() = "virtual directory classpath: " + origin.getOrElse("?")
  }
}
