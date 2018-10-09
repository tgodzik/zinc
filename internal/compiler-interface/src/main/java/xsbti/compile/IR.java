package xsbti.compile;

import java.io.File;

/**
 * Encodes a per-file compilation IR used by Scala 2 and Scala 3 compilers to
 * provide build pipelining.
 */
public class IR {
    private String name;
    private File associatedOutput;
    private byte[] content;

    public IR(String name, File associatedOutput, byte[] content) {
        this.name = name;
        this.associatedOutput = associatedOutput;
        this.content = content;
    }

    /**
     * Returns a Zinc fully qualified name separated by `/` associated to the
     * symbol that the given IR represents.
     */
    public String name() {
        return name;
    }

    /**
     * Returns all the name components of `name`.
     */
    public String[] nameComponents() {
        return name.split("/");
    }

    /**
     * Returns the associated output to the IR. The output file can refer to
     * either a JAR or a classes directory. In the presence of multiple classes
     * directory, only the one associated to the symbol that created this IR
     * is used.
     *
     * This information is required by the incremental compiler to know where a
     * given IR comes from. It's thus critical to correctly register dependencies
     * (check `processDependency` in the `Dependency` incremental phase in the
     * Scala 2 bridge for more information).
     *
     * @return The output file associated with it.
     */
    public File associatedOutput() {
        return associatedOutput;
    }

    /**
     * Returns a generic representation of a compilation IR (be it Scala 2 pickles
     * or Scala 3 Tasty trees).
     */
    public byte[] content() {
        return content;
    }
}
