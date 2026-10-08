package be.imgn.inject;

import java.io.Serial;

/** Thrown when two factories expose the same {@link Key}. */
public class DuplicateBindingException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public DuplicateBindingException(String message) {
        super(message);
    }
}
