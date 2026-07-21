package be.imgn.inject.context;

import java.lang.annotation.Annotation;

/**
 * Decides whether a bean carrying a given conditioning annotation is registered. Implementations are discovered via
 * {@link java.util.ServiceLoader} and keyed by {@link #annotationType()}.
 *
 * <p>This is the framework's first runtime SPI that reads annotation attribute values: {@link #matches} receives the
 * {@link AnnotationValue} captured at compile time, so a condition can branch on the annotation's members.
 */
public interface Condition {

    /** {@return the conditioning annotation this condition handles} */
    Class<? extends Annotation> annotationType();

    /**
     * {@return whether the bean should be registered}
     *
     * @param context the evaluation environment
     * @param annotation the conditioning annotation's captured value, including its members
     */
    boolean matches(ConditionContext context, AnnotationValue annotation);
}
