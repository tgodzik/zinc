package scala.tools.nsc

import java.net.URL

import xsbt.{PickleVirtualDirectory, PicklerGen}
import xsbti.compile.{EmptyIRStore, IR, IRStore}

import scala.tools.nsc.classpath.{AggregateClassPath, VirtualDirectoryClassPath}
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.util.ClassPath

trait ZincPicklePath {
  self: Global =>

  private[this] var store0: IRStore = EmptyIRStore.getStore()

  /** Returns the active IR store, set by [[setUpIRStore()]] and cleared by [[clearStore()]]. */
  def store: IRStore = store0

  def clearStore(store: IRStore): Unit = {
    this.store0 = EmptyIRStore.getStore()
    store.getDependentsIRs.foreach { irs =>
      PicklerGen.removeCacheForIRs(irs)
    }
  }

  private[this] var originalClassPath: ClassPath = null
  def setUpIRStore(store: IRStore): Unit = {
    this.store0 = store
    val rootPickleDirs = store.getDependentsIRs.map(PicklerGen.toVirtualDirectory(_))
    val pickleClassPaths = rootPickleDirs.map(new ZincVirtualDirectoryClassPath(_)).toList

    // We do this so that when resident compilation is enabled, pipelining works
    if (originalClassPath == null) {
      originalClassPath = platform.classPath
    }

    val allClassPaths = pickleClassPaths ::: List(originalClassPath)
    val newClassPath = AggregateClassPath.createAggregate(allClassPaths: _*)
    platform.currentClassPath = Some(newClassPath)
  }

  // We need to override `asURLs` so that the macro classloader ignores pickle paths
  final class ZincVirtualDirectoryClassPath(dir: VirtualDirectory)
      extends VirtualDirectoryClassPath(dir) {
    override def asURLs: Seq[URL] = Nil
  }
}
