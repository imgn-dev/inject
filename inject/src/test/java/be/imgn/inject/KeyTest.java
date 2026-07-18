package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class KeyTest {
    @Test
    void simpleKeyHasTypeAndNoQualifier() {
        Key<String> key = Key.of(String.class);
        assertThat(key)
                .isInstanceOf(SimpleKey.class)
                .returns(String.class, Key::type)
                .returns(Optional.empty(), Key::qualifier);
    }

    @Test
    void qualifiedKeysDifferByAttributeValue() {
        Qualifier db = new Qualifier(Named.class, Map.of("value", "db"));
        Qualifier cache = new Qualifier(Named.class, Map.of("value", "cache"));
        assertThat(Key.of(String.class, db))
                .isInstanceOf(QualifiedKey.class)
                .isEqualTo(Key.of(String.class, db))
                .isNotEqualTo(Key.of(String.class, cache));
    }

    @Test
    @SuppressWarnings("NullAway")
    void ofRejectsNulls() {
        Class<Object> nullType = null;
        assertThatNullPointerException().isThrownBy(() -> Key.of(nullType));
    }
}
