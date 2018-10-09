package xsbt

import xsbti.compile.IR

import scala.collection.mutable
import scala.reflect.internal.FatalError
import scala.reflect.internal.pickling.PickleBuffer
import scala.reflect.io.AbstractFile
import scala.tools.nsc.Phase

final class PicklerGen(val global: CallbackGlobal) extends Compat with GlobalHelpers {
  import global._

  def newPhase(prev: Phase): Phase = new PicklerGenPhase(prev)
  private final class PicklerGenPhase(prev: Phase) extends GlobalPhase(prev) {
    override def description = "Populates in-memory pickles to be shared by Zinc instances."
    def name = PicklerGen.name
    def apply(unit: global.CompilationUnit): Unit = ()

    // Keep in mind that we need to index based on the flattened name
    override def run(): Unit = {
      val start = System.currentTimeMillis
      global.foundMacroLocation match {
        case Some(location) =>
          val msg = s"Found macro at $location; pipelining is disabled for this module."
          if (settings.fatalWarnings) inform(msg) else warning(msg)
        case None =>
          super.run()
          val irs = toIRs(global.currentRun.symData)
          callback.irCompleted(irs)
      }
      val stop = System.currentTimeMillis
      debuglog("Picklergen phase took : " + ((stop - start) / 1000.0) + " s")
    }

    def toIRs(pickles: mutable.Map[global.Symbol, PickleBuffer]): Array[IR] = {
      pickles.toArray.flatMap {
        case (symbol, pickle) =>
          val javaName = symbol.javaBinaryNameString
          val associatedOutput = global.settings.outputDirs.outputDirFor(symbol.associatedFile).file
          val bytes = pickle.bytes.take(pickle.writeIndex)
          if (symbol.isModule) {
            if (symbol.companionClass == NoSymbol) {
              val companionJavaName = symbol.fullName('/')
              debuglog(s"Companion java name ${companionJavaName} vs name $javaName")

              /**
               * Scalac's completion engine assumes that for every module there
               * is always a companion class. This invariant is preserved in
               * `genbcode` because a companion class file is always generated.
               *
               * Here, we simulate the same behaviour. If a module has no companion
               * class, we create one that has no pickle information. We will filter
               * it out in `toIndex`, but still generate a fake class file for it
               * in `toVirtualFile`.
               */
              Array(
                new IR(javaName, associatedOutput, bytes),
                new IR(companionJavaName, associatedOutput, Array())
              )
            } else Array(new IR(javaName, associatedOutput, bytes))
          } else {
            Array(new IR(javaName, associatedOutput, bytes))
          }
      }
    }

  }
}

object PicklerGen {
  def name = "picklergen"
  final val rootStartId = "☣☖"

  object PickleFile {
    import java.io.File
    def unapply(arg: AbstractFile): Option[File] = {
      arg match {
        case vf: PickleVirtualFile =>
          // The dependency comes from an in-memory ir (build pipelining is enabled)
          Some(new File(vf.ir.associatedOutput(), vf.path.stripPrefix(PicklerGen.rootStartId)))
        case _ => None
      }
    }
  }

  /**
    * Transforms IRs containing Scala pickles to in-memory virtual directories.
    *
    * This transformation is done in every compiler run (called by `ZincPicklePath`).
    *
    * @param irs A sequence of Scala 2 IRs to turn into a pickle virtual directory.
    * @return The root virtual directory containing all the pickle files of this compilation unit.
    */
  def toVirtualDirectory(irs: Array[IR]): PickleVirtualDirectory = {
    val root = new PickleVirtualDirectory(PicklerGen.rootStartId, None)
    irs.foreach { ir =>
      ir.nameComponents() match {
        case Array() => throw new FatalError(s"Unexpected empty path component for ${ir}.")
        case paths =>
          val parent = paths.init.foldLeft(root) {
            case (enclosingDir, dirName) =>
              enclosingDir.subdirectoryNamed(dirName).asInstanceOf[PickleVirtualDirectory]
          }
          parent.pickleFileNamed(s"${paths.last}.class", ir)
      }
    }
    root
  }
}
