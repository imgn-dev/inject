package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Contributes the annotated bean (or {@code @Provides} result) to the {@code Set<value>} binding instead of binding it
 * under its own keys. Inject the collection as {@code Set<value>}; iteration order is unspecified.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IntoSet {
    /**
     * Returns the set's element type; the bean must be assignable to it.
     *
     * @return the set's element type; the bean must be assignable to it
     */
    Class<?> value();
}
