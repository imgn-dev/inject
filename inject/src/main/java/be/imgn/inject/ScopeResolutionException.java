package be.imgn.inject;

import java.io.Serial;

/** Thrown when a scoping annotation has no registered {@link be.imgn.inject.context.Scope}, or more than one. */
public class ScopeResolutionException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public ScopeResolutionException(String message) {
        super(message);
    }
}
