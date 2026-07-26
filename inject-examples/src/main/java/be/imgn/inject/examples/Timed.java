package be.imgn.inject.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InterceptingAnnotation;

/** Records entry and exit around a call, tagged with {@link #value()}. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Timed {
    /** {@return the tag recorded with the call; defaults to the method name} */
    String value() default "";
}
