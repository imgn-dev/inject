package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method invoked once the bean is fully injected — after constructor, {@code @Inject} methods, and
 * {@code @Inject} fields. At most one per class; the method must be a non-private, non-static, no-argument {@code void}
 * method declared on the bean itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostConstruct {}
