package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Singleton} bean, or a {@link Provides} method returning a singleton, to be built when the root
 * injector is created rather than on first resolution.
 *
 * <p>Eager singletons are built on the thread creating the injector, after the {@link ConstructionListener}s are
 * resolved, so the listeners see them and their dependencies. Their dependencies are built first; beyond that their
 * order is unspecified. A bean whose condition fails, a {@link Default} binding that lost its key, and a bean every key
 * of which a builder override shadows are not built. A failure fails the injector's creation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Eager {}
