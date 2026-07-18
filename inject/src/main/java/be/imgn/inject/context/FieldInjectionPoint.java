package be.imgn.inject.context;

import java.util.List;
import java.util.Objects;

import be.imgn.inject.Key;

/**
 * A field injection point.
 *
 * @param name the field name (for inspection)
 * @param key the field's dependency key
 */
public record FieldInjectionPoint(String name, Key<?> key) implements InjectionPoint {
    /**
     * @param name the field name
     * @param key the field's dependency key
     */
    public FieldInjectionPoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(key, "key");
    }

    @Override
    public List<Key<?>> keys() {
        return List.of(key);
    }
}
