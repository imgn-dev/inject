package be.imgn.inject;

/** Thrown when a scoping annotation has no registered {@link be.imgn.inject.context.Scope}. */
public class ScopeResolutionException extends InjectionException {
    /** @param message the detail message */
    public ScopeResolutionException(String message) {
        super(message);
    }
}
