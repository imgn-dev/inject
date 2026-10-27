package be.imgn.inject;

import java.io.Serial;

/**
 * Thrown when a scope cannot apply: more than one {@link be.imgn.inject.context.Scope} for a scoping annotation, a bean
 * of a child-local scope resolved where no injector hosts that scope, or a child-local bean contributing to a
 * collection.
 */
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
