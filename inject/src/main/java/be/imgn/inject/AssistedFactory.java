package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a factory-interface method the annotation processor implements. The method's parameters supply the recipe's
 * {@link Assisted} parameters (matched by type and tag); the container resolves everything else per call.
 *
 * <p>Link resolution: with no members, the method's return type is the implementation class; with {@link #value()}, the
 * implementation is {@code value()} and the return type may be any supertype of it; with {@link #value()} and
 * {@link #method()}, the recipe is the named assisted {@code @Provides} method declared by {@code value()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AssistedFactory {
    /**
     * Returns the implementation class, or the recipe's declaring class when {@link #method()} is set.
     *
     * @return the implementation class, or the recipe's declaring class when {@link #method()} is set
     */
    Class<?> value() default void.class;

    /**
     * Returns the name of an assisted {@code @Provides} method on {@link #value()}; empty for constructor recipes.
     *
     * @return the name of an assisted {@code @Provides} method on {@link #value()}; empty for constructor recipes
     */
    String method() default "";
}
