/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package xsbti.compile;

import xsbti.AnalysisCallback;
import xsbti.Logger;
import xsbti.Reporter;

import java.io.File;
import java.util.Optional;

/**
 * Represent the interface of a Scala compiler.
 */
public interface ScalaCompiler {
    /**
     * Return the {@link ScalaInstance} used by this instance of the compiler.
     */
    ScalaInstance scalaInstance();

    /**
     * Recompile the subset of <code>sources</code> impacted by the
     * changes defined in <code>changes</code> and collect the new APIs.
     *
     * @param sources  All the sources of the project.
     * @param changes  The changes that have been detected at the previous step.
     * @param callback The callback to which the extracted information should be
     *                 reported.
     * @param log      The logger in which the Scala compiler will log info.
     * @param reporter The reporter to which errors and warnings should be
     *                 reported during compilation.
     * @param progress Where to report the file being currently compiled.
     * @param compiler The actual compiler that will perform the compilation step.
     *
     * @deprecated Use {@link #compile(File[], DependencyChanges, String[], Output, AnalysisCallback, Reporter, GlobalsCache, Logger, Optional, IRStore, File[])} instead,
     * which specifies the IR store to be used for compilation.
     */
    @Deprecated
    void compile(File[] sources,
                 DependencyChanges changes,
                 AnalysisCallback callback,
                 Logger log,
                 Reporter reporter,
                 CompileProgress progress,
                 CachedCompiler compiler);

    /**
     * Recompile the subset of <code>sources</code> impacted by the
     * changes defined in <code>changes</code> and collect the new APIs.
     *
     * @param sources  All the sources of the project.
     * @param changes  The changes that have been detected at the previous step.
     * @param callback The callback to which the extracted information should be
     *                 reported.
     * @param log      The logger in which the Scala compiler will log info.
     * @param reporter The reporter to which errors and warnings should be
     *                 reported during compilation.
     * @param progress Where to report the file being currently compiled.
     * @param compiler The actual compiler that will perform the compilation step.
     */
    void compile(File[] sources,
                 DependencyChanges changes,
                 AnalysisCallback callback,
                 Logger log,
                 Reporter reporter,
                 CompileProgress progress,
                 IRStore store,
                 File[] invalidatedClassFiles,
                 CachedCompiler compiler);

    /**
     * Recompile the subset of <code>sources</code> impacted by the
     * changes defined in <code>changes</code> and collect the new APIs.
     *
     * @param sources     All the sources of the project.
     * @param changes     The changes that have been detected at the previous step.
     * @param options     The arguments to give to the Scala compiler.
     *                    For more information, run `scalac -help`.
     * @param output      The location where generated class files should be put.
     * @param callback    The callback to which the extracted information should
     *                    be reported.
     * @param reporter    The reporter to which errors and warnings should be
     *                    reported during compilation.
     * @param cache       The cache from where we retrieve the compiler to use.
     * @param log         The logger in which the Scala compiler will log info.
     * @param progressOpt The progress interface in which the Scala compiler
     *                    will report on the file being compiled.
     *
     * @deprecated Use {@link #compile(File[], DependencyChanges, String[], Output, AnalysisCallback, Reporter, GlobalsCache, Logger, Optional, IRStore)} instead,
     * which specifies the IR store to be used for compilation.
     */
    @Deprecated
    void compile(File[] sources,
                 DependencyChanges changes,
                 String[] options,
                 Output output,
                 AnalysisCallback callback,
                 Reporter reporter,
                 GlobalsCache cache,
                 Logger log,
                 Optional<CompileProgress> progressOpt);

    /**
     * Recompile the subset of <code>sources</code> impacted by the
     * changes defined in <code>changes</code> and collect the new APIs.
     *
     * @param sources     All the sources of the project.
     * @param changes     The changes that have been detected at the previous step.
     * @param options     The arguments to give to the Scala compiler.
     *                    For more information, run `scalac -help`.
     * @param output      The location where generated class files should be put.
     * @param callback    The callback to which the extracted information should
     *                    be reported.
     * @param reporter    The reporter to which errors and warnings should be
     *                    reported during compilation.
     * @param cache       The cache from where we retrieve the compiler to use.
     * @param log         The logger in which the Scala compiler will log info.
     * @param progressOpt The progress interface in which the Scala compiler
     *                    will report on the file being compiled.
     * @param store       The store that contains the IR files to populate the symbol table
     *                    under build pipelining.
     */
    void compile(File[] sources,
                 DependencyChanges changes,
                 String[] options,
                 Output output,
                 AnalysisCallback callback,
                 Reporter reporter,
                 GlobalsCache cache,
                 Logger log,
                 Optional<CompileProgress> progressOpt,
                 IRStore store,
                 File[] invalidatedClassFiles);

    /**
     * Reset the global IR caches that are persisted across different
     * runs. These caches contain ir-related classpaths that are expensive to
     * construct and their in-memory representation.
     *
     * @param store  The IR store that contains the IRs whose state we want to reset.
     * @param cached The cached compiler that enables us to call the reset method.
     * @param log    A logger for reporting issues when resetting the state.
     */
    void resetGlobalIRCaches(IRStore store, CachedCompiler cached, Logger log);
}
