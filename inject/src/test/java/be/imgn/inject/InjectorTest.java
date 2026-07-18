package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import be.imgn.inject.context.Context;

class InjectorTest {
    private static final class StubContext implements Context {
        @Override
        public <T> Provider<T> getProvider(Key<T> key) {
            if (key.equals(Key.of(String.class))) {
                @SuppressWarnings("unchecked")
                Provider<T> provider = (Provider<T>) (Provider<String>) () -> "leaf";
                return provider;
            }
            throw new UnsatisfiedDependencyException("No binding for " + key);
        }
    }

    @Test
    void convenienceMethodsDelegateToProviderByKey() {
        Injector injector = new DefaultInjector(new StubContext());
        assertThat(injector.getInstance(String.class)).isEqualTo("leaf");
        assertThat(injector.getProvider(Key.of(String.class)).get()).isEqualTo("leaf");
        assertThat(injector.getInstance(Key.of(String.class))).isEqualTo("leaf");
    }

    @Test
    @SuppressWarnings("NullAway")
    void nullArgumentsRejected() {
        Injector injector = new DefaultInjector(new StubContext());
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
