package be.imgn.inject;

import java.io.Serial;

/** Thrown when an intercepting annotation is in use but no bean is annotated {@link Intercepts} for it. */
public class InterceptorResolutionException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

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
