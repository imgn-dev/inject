package be.imgn.inject;

import java.io.Serial;

/**
 * Thrown when a conditioning annotation has no registered {@link be.imgn.inject.context.Condition}, or more than one.
 */
public class ConditionResolutionException extends InjectionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public ConditionResolutionException(String message) {
        super(message);
    }
}
