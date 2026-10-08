package be.imgn.inject.log;

import be.imgn.inject.InjectingAnnotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a {@link java.util.logging.Logger} field to be injected with a logger named after its declaring type. */
@InjectingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Log {}
