package xsbti.compile;

// Defined as an independent class because static methods in interfaces are not allowed <= JDK 8
public class EmptyIRStore implements IRStore {
    public EmptyIRStore() {}

    public static EmptyIRStore getStore() {
        return new EmptyIRStore();
    }

    @Override
    public IR[][] getDependentsIRs() {
        return new IR[0][0];
    }

    @Override
    public IRStore merge(IRStore other) {
        return other;
    }
}
