package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers the annotated bean only when a property matches. Evaluated at container construction:
 *
 * <ul>
 *   <li>the property is absent — matches only if {@link #matchIfMissing()} is {@code true};
 *   <li>{@link #havingValue()} is empty — matches whenever the property is present, with any value;
 *   <li>{@link #havingValue()} is set — matches only when the property equals it.
 * </ul>
 *
 * <p>Properties come from the container's condition context (system properties by default).
 */
@ConditioningAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ConditionalOnProperty {

    /** {@return the property name to test} */
    String value();

    /** {@return the required property value; empty means "present with any value"} */
    String havingValue() default "";

    /** {@return whether the bean is registered when the property is absent} */
    boolean matchIfMissing() default false;
}
