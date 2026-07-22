package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class InjectorTest {
    @Test
    void convenienceMethodsDelegateToProviderByKey() {
        Injector injector = Injector.builder().bind(String.class, "leaf").build();
        assertThat(injector.getInstance(String.class)).isEqualTo("leaf");
        assertThat(injector.getProvider(Key.of(String.class)).get()).isEqualTo("leaf");
        assertThat(injector.getInstance(Key.of(String.class))).isEqualTo("leaf");
    }

    @Test
    @SuppressWarnings("NullAway")
    void nullArgumentsRejected() {
        Injector injector = Injector.builder().bind(String.class, "leaf").build();
        assertThatNullPointerException().isThrownBy(() -> injector.getInstance((Class<?>) null));
        assertThatNullPointerException().isThrownBy(() -> injector.getProvider((Key<?>) null));
    }

    @Test
    void createWiresUpServiceLoaderInjectorWithoutThrowing() {
        Injector injector = Injector.create();
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> injector.getInstance(Long.class));
    }
}
