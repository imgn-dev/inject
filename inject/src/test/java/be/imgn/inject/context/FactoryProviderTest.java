package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

class FactoryProviderTest {
    record Bean() {}

    static final class BeanFactory extends Factory<Bean> {
        BeanFactory() {
            super(Bean.class);
        }

        @Override
        protected Bean createInstance(Context context) {
            return new Bean();
        }
    }

    @Test
    void getBuildsViaFactory() {
        Context context = new Context() {
            @Override
            public <T> Provider<T> getProvider(Key<T> key) {
                throw new AssertionError("no dependencies expected");
            }
        };
        assertThat(new FactoryProvider<>(context, new BeanFactory()).get()).isInstanceOf(Bean.class);
    }
}
