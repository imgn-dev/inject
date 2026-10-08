package be.imgn.inject;

import java.io.Serial;

/** Thrown when a dependency cycle is detected at resolution time. */
public class CircularDependencyException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message, including the cycle path
     */
    public CircularDependencyException(String message) {
        super(message);
    }
}
