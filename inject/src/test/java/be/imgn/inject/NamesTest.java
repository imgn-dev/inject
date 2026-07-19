package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class NamesTest {
    @Test
    void namedInstancesObeyTheAnnotationEqualityContract() {
        Named db = Names.named("db");
        assertThat(db)
                .returns("db", Named::value)
                .returns(Named.class, Named::annotationType)
                .isEqualTo(Names.named("db"))
                .isNotEqualTo(Names.named("cache"));
        assertThat(db.hashCode()).isEqualTo(Names.named("db").hashCode());
    }

    @Test
    void hashCodeMatchesTheAnnotationSpec() {
        // (127 * "value".hashCode()) ^ "db".hashCode()
        assertThat(Names.named("db").hashCode()).isEqualTo((127 * "value".hashCode()) ^ "db".hashCode());
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullValue() {
        assertThatNullPointerException().isThrownBy(() -> Names.named(null));
    }
}
