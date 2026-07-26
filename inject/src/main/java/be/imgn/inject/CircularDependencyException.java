package be.imgn.inject;

/** Thrown when a dependency cycle is detected at resolution time. */
public class CircularDependencyException extends InjectionException {
    /**
     * Creates the exception.
     *
     * @param message the detail message, including the cycle path
     */
    public CircularDependencyException(String message) {
        super(message);
    }
}
