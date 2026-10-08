package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as the {@link be.imgn.inject.context.MethodInterceptor} handling one intercepting annotation. The bean
 * is an ordinary bean in every other respect: it is injected, scoped, and conditioned like any other.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Intercepts {
    /**
     * Returns the intercepting annotation this interceptor handles.
     *
     * @return the intercepting annotation this interceptor handles
     */
    Class<? extends Annotation> value();
}
