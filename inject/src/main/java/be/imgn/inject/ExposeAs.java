package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Narrows the keys a bean is exposed under to exactly the listed types, replacing the default (the bean type plus all
 * of its supertypes). Each listed type must be a supertype of the bean; nothing else — not even the bean's own type —
 * remains injectable unless listed. Any qualifier on the bean applies to every exposed key.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExposeAs {
    /** {@return the types the bean is exposed as; must be non-empty and each a supertype of the bean} */
    Class<?>[] value();
}
