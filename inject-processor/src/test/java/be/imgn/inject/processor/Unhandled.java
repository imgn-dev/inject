package be.imgn.inject.processor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InjectingAnnotation;

/** Test injecting annotation that no registered plugin handles. */
@InjectingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unhandled {}
