package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method invoked when the owning injector closes: for a bean of a child-local scope, the child injector that
 * hosts it. Only a scoped bean may declare it — prototypes and assisted beans are caller-owned and never destroyed by
 * the container. At most one per class; the method must be a non-private, non-static, no-argument {@code void} method
 * declared on the bean itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreDestroy {}
