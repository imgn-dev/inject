package be.imgn.inject;

/** Thrown when an intercepting annotation is in use but no bean is annotated {@link Intercepts} for it. */
public class InterceptorResolutionException extends InjectionException {
    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public InterceptorResolutionException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InterceptorResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
