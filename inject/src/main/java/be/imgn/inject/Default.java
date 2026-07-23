package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Provides} method as the fallback recipe for its key: the binding is used only when no other live
 * binding satisfies the same key. Two live defaults for one key are a configuration error
 * ({@link DuplicateBindingException} at bootstrap).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Default {}
