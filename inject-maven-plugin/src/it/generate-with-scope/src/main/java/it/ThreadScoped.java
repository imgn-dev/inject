package it;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import be.imgn.inject.ScopingAnnotation;

/** A custom scoping annotation, registered through {@link ThreadScope}. */
@ScopingAnnotation
@Retention(RetentionPolicy.RUNTIME)
public @interface ThreadScoped {}
