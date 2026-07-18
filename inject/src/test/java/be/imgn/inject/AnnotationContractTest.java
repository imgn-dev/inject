package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

class AnnotationContractTest {
    @Test
    void metaAnnotationsTargetAnnotationTypeOnly() {
        assertThat(QualifyingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
        assertThat(ScopingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
        assertThat(InjectingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
    }

    @Test
    void namedIsAQualifyingAnnotationWithValue() throws Exception {
        assertThat(Named.class.isAnnotationPresent(QualifyingAnnotation.class)).isTrue();
        assertThat(Named.class.getMethod("value").getReturnType()).isEqualTo(String.class);
    }

    @Test
    void singletonIsAScopingAnnotation() {
        assertThat(Singleton.class.isAnnotationPresent(ScopingAnnotation.class)).isTrue();
    }

    @Test
    void injectTargetsMembersAndRetainsAtRuntimeForTests() {
        assertThat(Inject.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
    }
}
