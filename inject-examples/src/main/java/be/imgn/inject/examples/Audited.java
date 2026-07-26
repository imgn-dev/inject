package be.imgn.inject.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InterceptingAnnotation;

/** Records an audit entry around every call to the annotated bean. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Audited {}
