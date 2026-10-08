package be.imgn.inject;

import java.io.Serial;

/** Thrown when no factory can satisfy a requested {@link Key}. */
public class UnsatisfiedDependencyException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public UnsatisfiedDependencyException(String message) {
        super(message);
    }
}
