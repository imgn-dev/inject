package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a caller-supplied parameter of an assisted recipe: an {@code @Inject} constructor or {@code @Provides} method
 * containing one becomes reachable only through an {@link AssistedFactory} method, which supplies these parameters at
 * call time while the container resolves the rest.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Assisted {
    /**
     * Returns the tag disambiguating same-typed assisted parameters; empty for untagged.
     *
     * @return the tag disambiguating same-typed assisted parameters; empty for untagged
     */
    String value() default "";
}
