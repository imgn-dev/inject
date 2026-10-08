package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Contributes the annotated bean (or {@code @Provides} result) to the {@code Map<String, value>} binding under
 * {@link #key()}, instead of binding it under its own keys. Two contributions with the same element type and key fail
 * at container construction.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IntoMap {
    /**
     * Returns the map's value type; the bean must be assignable to it.
     *
     * @return the map's value type; the bean must be assignable to it
     */
    Class<?> value();

    /**
     * Returns the map key for this contribution; must not be blank.
     *
     * @return the map key for this contribution; must not be blank
     */
    String key();
}
