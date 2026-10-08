package be.imgn.inject.examples.modular;

import be.imgn.inject.InterceptingAnnotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a call to be prefixed by the tracing interceptor. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Traced {}
