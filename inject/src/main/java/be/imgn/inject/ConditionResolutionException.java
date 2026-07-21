package be.imgn.inject;

/** Thrown when a conditioning annotation has no registered {@link be.imgn.inject.context.Condition}. */
public class ConditionResolutionException extends InjectionException {
    /** @param message the detail message */
    public ConditionResolutionException(String message) {
        super(message);
    }
}
