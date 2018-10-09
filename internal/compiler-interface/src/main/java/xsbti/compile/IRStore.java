package xsbti.compile;

/**
 * A store used by the compiler bridge to populate the symbol table from the IRs
 * collected in it instead of class files. It also allows the compiler bridge to
 * store the IR associated with a given project.
 */
public interface IRStore {
   /**
    * Returns all IRs in this store. This method is usually called in the bridge
    * to obtain all the IRs of the dependent modules of a project to populate the
    * symbol table.
    */
   IR[] getDependentsIRs();

   /**
    * Merges two store instances and its corresponding IRs.
    *
    * If there are IR instances associated with the same output that clash with
    * the same name, the IRs of the `other` store take precedence over the ones
    * in the first store.
    *
    * This operation is used to merge the products of incremental compiler runs
    * in the context of build pipelining.
    *
    * @param other The store we want to merge with the current one.
    * @return A store containing the IR instances of both stores.
    */
   IRStore merge(IRStore other);
}
