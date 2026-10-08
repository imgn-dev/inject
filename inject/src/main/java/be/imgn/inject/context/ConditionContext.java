package be.imgn.inject.context;

import java.util.Optional;

/**
 * The environment a {@link Condition} evaluates against. Kept deliberately small: today it exposes properties, sourced
 * from the container's system properties by default.
 */
@FunctionalInterface
public interface ConditionContext {

    /**
     * Returns the value of property {@code name}, if set.
     *
     * @param name the property name
     * @return the value of property {@code name}, if set
     */
    Optional<String> property(String name);
}
