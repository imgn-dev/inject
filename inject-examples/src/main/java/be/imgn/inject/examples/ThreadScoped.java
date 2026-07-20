package be.imgn.inject.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.ScopingAnnotation;

/**
 * Example user-defined scope: one instance per thread, resolved by {@link ThreadScope}. Demonstrates the {@code Scope}
 * SPI — a scoping annotation is any annotation meta-annotated with {@link ScopingAnnotation}, paired with a
 * {@code Scope} service that handles it.
 */
@ScopingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ThreadScoped {}
