package be.imgn.inject;

/** Thrown when no factory can satisfy a requested {@link Key}. */
public class UnsatisfiedDependencyException extends InjectionException {
    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public UnsatisfiedDependencyException(String message) {
        super(message);
    }
}
