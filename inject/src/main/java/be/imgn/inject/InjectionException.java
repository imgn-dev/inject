package be.imgn.inject;

import java.io.Serial;

/** Base type for all runtime injection failures. */
public class InjectionException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public InjectionException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
