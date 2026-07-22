package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Types;

class ContributionTest {
    @Test
    void holdsCollectionKeyAndOptionalMapKey() {
        Key<?> key = Key.of(Types.setOf(String.class));
        assertThat(new Contribution(key, null))
                .returns(key, Contribution::collectionKey)
                .returns(null, Contribution::mapKey);
        assertThat(new Contribution(Key.of(Types.mapOf(String.class, String.class)), "auth").mapKey())
                .isEqualTo("auth");
    }

    @Test
    void factoriesContributeNothingByDefault() {
        Factory<Object> factory = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        };
        assertThat(factory.contribution()).isEmpty();
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullCollectionKey() {
        assertThatNullPointerException().isThrownBy(() -> new Contribution(null, "x"));
    }
}
