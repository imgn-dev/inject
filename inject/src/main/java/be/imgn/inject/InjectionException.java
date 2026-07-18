package be.imgn.inject;

/** Base type for all runtime injection failures. */
public class InjectionException extends RuntimeException {
    /** @param message the detail message */
    public InjectionException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
