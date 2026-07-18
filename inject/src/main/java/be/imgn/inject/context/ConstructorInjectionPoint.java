package be.imgn.inject.context;

import java.util.List;
import java.util.Objects;

import be.imgn.inject.Key;

/**
 * The constructor injection point of a bean.
 *
 * @param keys the constructor parameter keys, in order
 */
public record ConstructorInjectionPoint(List<Key<?>> keys) implements InjectionPoint {
    /** @param keys the constructor parameter keys, in order */
    public ConstructorInjectionPoint {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }

    /** {@return a no-argument constructor injection point} */
    public static ConstructorInjectionPoint none() {
        return new ConstructorInjectionPoint(List.of());
    }
}
