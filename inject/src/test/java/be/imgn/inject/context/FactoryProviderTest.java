package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import org.junit.jupiter.api.Test;

class FactoryProviderTest {
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
}
