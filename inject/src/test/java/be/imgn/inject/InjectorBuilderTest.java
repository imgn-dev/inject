package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class InjectorBuilderTest {
    @Test
    void builderBindsInstanceByType() {
        Injector injector = Injector.builder().bind(String.class, "fake").build();
        assertThat(injector.getInstance(String.class)).isEqualTo("fake");
        assertThat(injector.getInstance(Key.of(String.class))).isEqualTo("fake");
    }

    @Test
    void builderBindsInstanceByKey() {
        Injector injector = Injector.builder()
                .bind(Key.of(String.class, Names.named("db")), "jdbc")
                .build();
        assertThat(injector.getInstance(Key.of(String.class, Names.named("db"))))
                .isEqualTo("jdbc");
    }

    @Test
    void laterBindWinsForSameKey() {
        Injector injector = Injector.builder()
                .bind(String.class, "first")
                .bind(String.class, "second")
                .build();
        assertThat(injector.getInstance(String.class)).isEqualTo("second");
    }

    @Test
    @SuppressWarnings("NullAway")
    void builderRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> Injector.builder().bind(String.class, null));
    }
}
