package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Type;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KeyTest {
    @Test
    void unqualifiedKeyHasTypeAndNoQualifier() {
        Key<String> key = Key.of(String.class);
        assertThat(key).returns(String.class, Key::type).returns(false, Key::hasQualifier);
        assertThat(key.qualifier()).isNull();
    }

    @Test
    void qualifiedKeysDifferByAttributeValue() {
        Qualifier db = new Qualifier(Named.class, Map.of("value", "db"));
        Qualifier cache = new Qualifier(Named.class, Map.of("value", "cache"));
        assertThat(Key.of(String.class, db))
                .returns(true, Key::hasQualifier)
                .returns(db, Key::qualifier)
                .isEqualTo(Key.of(String.class, db))
                .isNotEqualTo(Key.of(String.class, cache))
                .isNotEqualTo(Key.of(String.class));
    }

    @Test
    void typeOverloadEqualsClassOverload() {
        Type stringType = String.class;
        assertThat(Key.of(stringType, null)).isEqualTo(Key.of(String.class));
    }

    @Test
    @SuppressWarnings("NullAway")
    void ofRejectsNullType() {
        Class<Object> nullType = null;
        assertThatNullPointerException().isThrownBy(() -> Key.of(nullType));
    }
}
