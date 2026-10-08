package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.Named;
import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.ScopingAnnotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnnotationValueTest {
    private enum Level {
        DEBUG
    }

    @Test
    void exposesTypedMemberValues() {
        AnnotationValue value = AnnotationValue.of(
                Named.class,
                Map.of(
                        "value",
                        "db",
                        "count",
                        5,
                        "enabled",
                        true,
                        "target",
                        String.class,
                        "level",
                        Level.DEBUG,
                        "tags",
                        List.of("a", "b")),
                Set.of(QualifyingAnnotation.class));
        assertThat(value)
                .returns(Named.class, AnnotationValue::annotationType)
                .returns(Optional.of("db"), AnnotationValue::stringValue)
                .returns(Optional.of(5), v -> v.intValue("count"))
                .returns(Optional.of(true), v -> v.booleanValue("enabled"))
                .returns(Optional.of(String.class), v -> v.classValue("target"))
                .returns(Optional.of(Level.DEBUG), v -> v.enumValue("level", Level.class))
                .returns(List.of("a", "b"), v -> v.listValue("tags", String.class));
    }

    @Test
    void missingOrMistypedMembersYieldEmpty() {
        AnnotationValue value = AnnotationValue.of(Named.class, Map.of("value", "db"), Set.of());
        assertThat(value)
                .returns(Optional.empty(), v -> v.stringValue("absent"))
                .returns(Optional.empty(), v -> v.intValue("value"))
                .returns(List.of(), v -> v.listValue("value", String.class));
    }

    @Test
    void reportsStereotypes() {
        AnnotationValue value = AnnotationValue.of(Named.class, Map.of(), Set.of(QualifyingAnnotation.class));
        assertThat(value)
                .returns(true, v -> v.hasStereotype(QualifyingAnnotation.class))
                .returns(false, v -> v.hasStereotype(ScopingAnnotation.class))
                .returns(Set.of(QualifyingAnnotation.class), AnnotationValue::stereotypes);
    }

    @Test
    void equalityIsTypeAndValues() {
        AnnotationValue db = AnnotationValue.of(Named.class, Map.of("value", "db"), Set.of());
        assertThat(db)
                .isEqualTo(AnnotationValue.of(Named.class, Map.of("value", "db"), Set.of()))
                .isNotEqualTo(AnnotationValue.of(Named.class, Map.of("value", "cache"), Set.of()));
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> AnnotationValue.of(null, Map.of(), Set.of()));
        assertThatNullPointerException().isThrownBy(() -> AnnotationValue.of(Named.class, null, Set.of()));
    }
}
