package be.imgn.inject;

/** Thrown when two factories expose the same {@link Key}. */
public class DuplicateBindingException extends InjectionException {
    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public DuplicateBindingException(String message) {
        super(message);
    }
}
