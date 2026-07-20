package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Named;
import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;

class AnnotationMetadataTest {
    private static final AnnotationValue NAMED_DB =
            AnnotationValue.of(Named.class, Map.of("value", "db"), Set.of(QualifyingAnnotation.class));
    private static final AnnotationValue SINGLETON =
            AnnotationValue.of(Singleton.class, Map.of(), Set.of(ScopingAnnotation.class));

    @Test
    void emptyHoldsNothing() {
        assertThat(AnnotationMetadata.empty())
                .returns(true, AnnotationMetadata::isEmpty)
                .returns(false, m -> m.isPresent(Named.class))
                .returns(Optional.empty(), m -> m.get(Named.class));
    }

    @Test
    void holdsAnnotationValuesByType() {
        AnnotationMetadata metadata = AnnotationMetadata.of(NAMED_DB);
        assertThat(metadata)
                .returns(false, AnnotationMetadata::isEmpty)
                .returns(true, m -> m.isPresent(Named.class))
                .returns(Set.of(Named.class), AnnotationMetadata::types);
        assertThat(metadata.get(Named.class)).hasValueSatisfying(named -> assertThat(named)
                .returns(Named.class, AnnotationValue::annotationType)
                .returns(Optional.of("db"), AnnotationValue::stringValue)
                .returns(true, v -> v.hasStereotype(QualifyingAnnotation.class)));
    }

    @Test
    void queriesAnnotationsByStereotype() {
        AnnotationMetadata metadata = AnnotationMetadata.of(NAMED_DB, SINGLETON);
        assertThat(metadata)
                .returns(true, m -> m.hasStereotype(ScopingAnnotation.class))
                .returns(List.of(NAMED_DB), m -> m.byStereotype(QualifyingAnnotation.class))
                .returns(List.of(), m -> m.byStereotype(InjectingAnnotation.class));
    }

    @Test
    void ofWithNoArgumentsReturnsEmpty() {
        assertThat(AnnotationMetadata.of()).isSameAs(AnnotationMetadata.empty());
    }

    @Test
    void laterAnnotationOfSameTypeWins() {
        AnnotationValue cache = AnnotationValue.of(Named.class, Map.of("value", "cache"), Set.of());
        assertThat(AnnotationMetadata.of(NAMED_DB, cache).get(Named.class))
                .hasValueSatisfying(named -> assertThat(named.stringValue()).hasValue("cache"));
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> AnnotationMetadata.of((AnnotationValue) null));
        assertThatNullPointerException()
                .isThrownBy(() -> AnnotationMetadata.empty().get(null));
    }
}
