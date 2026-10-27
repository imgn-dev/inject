package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation marking an annotation as a scope selector. A {@link be.imgn.inject.context.Scope} service handles the
 * scope, as {@code SingletonScope} handles {@link Singleton}; a scope with no service is child-local, and the child
 * injectors created by {@link Injector#scopedChildBuilder(Class)} host its instances.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ScopingAnnotation {}
