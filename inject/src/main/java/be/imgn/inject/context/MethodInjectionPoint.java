package be.imgn.inject.context;

import java.util.List;
import java.util.Objects;

import be.imgn.inject.Key;

/**
 * A method injection point.
 *
 * @param name the method name (for inspection)
 * @param keys the method parameter keys, in order
 */
public record MethodInjectionPoint(String name, List<Key<?>> keys) implements InjectionPoint {
    /**
     * @param name the method name
     * @param keys the method parameter keys, in order
     */
    public MethodInjectionPoint {
        Objects.requireNonNull(name, "name");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }
}
