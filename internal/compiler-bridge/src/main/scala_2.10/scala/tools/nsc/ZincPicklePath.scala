package scala.tools.nsc

import java.net.URI
import xsbti.compile.{IRStore, EmptyIRStore}

trait ZincPicklePath {
  self: Global =>

  private[this] var store0: IRStore = EmptyIRStore.getStore()

  /** Returns the active IR store, set by [[setUpIRStore()]] and cleared by [[clearStore()]]. */
  def store: IRStore = store0

  def clearStore(): Unit = {
    this.store0 = EmptyIRStore.getStore()
  }

  def setUpIRStore(store: IRStore): Unit = ()
}
