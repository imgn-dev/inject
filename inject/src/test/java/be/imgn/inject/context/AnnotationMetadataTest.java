package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Named;
import be.imgn.inject.Names;

class AnnotationMetadataTest {
    @Test
    void emptyHoldsNothing() {
        assertThat(AnnotationMetadata.empty())
                .returns(true, AnnotationMetadata::isEmpty)
                .returns(false, m -> m.isPresent(Named.class))
                .extracting(m -> m.get(Named.class))
                .isEqualTo(Optional.empty());
    }

    @Test
    void holdsAnnotationInstancesByType() {
        Named db = Names.named("db");
        AnnotationMetadata metadata = AnnotationMetadata.of(db);
        assertThat(metadata)
                .returns(false, AnnotationMetadata::isEmpty)
                .returns(true, m -> m.isPresent(Named.class))
                .returns(Set.of(Named.class), AnnotationMetadata::types);
        assertThat(metadata.get(Named.class))
                .hasValueSatisfying(named -> assertThat(named.value()).isEqualTo("db"));
    }

    @Test
    void ofWithNoArgumentsReturnsEmpty() {
        assertThat(AnnotationMetadata.of()).isSameAs(AnnotationMetadata.empty());
    }

    @Test
    void laterAnnotationOfSameTypeWins() {
        AnnotationMetadata metadata = AnnotationMetadata.of(Names.named("first"), Names.named("second"));
        assertThat(metadata.get(Named.class))
                .hasValueSatisfying(named -> assertThat(named.value()).isEqualTo("second"));
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> AnnotationMetadata.of((Annotation) null));
        assertThatNullPointerException()
                .isThrownBy(() -> AnnotationMetadata.empty().get(null));
    }
}
