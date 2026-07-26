package be.imgn.inject;

/** Thrown when an interceptor throws something the intercepted method does not declare, or returns an illegal value. */
public class InterceptionException extends InjectionException {
    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public InterceptionException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the throwable the interceptor raised
     */
    public InterceptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
