package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation marking an annotation as a registration condition. A bean annotated with a conditioning annotation is
 * registered only if the matching {@link be.imgn.inject.context.Condition} accepts it; otherwise the bean is absent
 * from the graph, exactly as if it had never been declared.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ConditioningAnnotation {}
