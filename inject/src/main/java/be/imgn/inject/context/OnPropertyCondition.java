package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.Optional;

import be.imgn.inject.ConditionalOnProperty;

/** Built-in {@link Condition} for {@link ConditionalOnProperty}. */
public final class OnPropertyCondition implements Condition {

    @Override
    public Class<? extends Annotation> annotationType() {
        return ConditionalOnProperty.class;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotationValue annotation) {
        String name = annotation
                .stringValue()
                .orElseThrow(() -> new IllegalStateException(
                        "@ConditionalOnProperty is missing its property name (value member)"));
        Optional<String> actual = context.property(name);
        if (actual.isEmpty()) {
            return annotation.booleanValue("matchIfMissing").orElse(false);
        }
        String havingValue = annotation.stringValue("havingValue").orElse("");
        return havingValue.isEmpty() || havingValue.equals(actual.get());
    }
}
