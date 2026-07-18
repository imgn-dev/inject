package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

class FactoryTest {
    /** A greeting bean whose message is injected via constructor. */
    record Greeting(String message) {}

    /** Minimal Context stub returning fixed providers. */
    static final class StubContext implements Context {
        private final Map<Key<?>, Provider<?>> bindings;

        StubContext(Map<Key<?>, Provider<?>> bindings) {
            this.bindings = bindings;
        }

        @Override
        @SuppressWarnings({"unchecked", "NullAway"})
        public <T> Provider<T> getProvider(Key<T> key) {
            return (Provider<T>) bindings.get(key);
        }
    }

    /** Hand-written factory standing in for generated output. */
    static final class GreetingFactory extends Factory<Greeting> {
        GreetingFactory() {
            super(Greeting.class);
        }

        @Override
        protected ConstructorInjectionPoint constructorInjectionPoint() {
            return new ConstructorInjectionPoint(List.of(Key.of(String.class)));
        }

        @Override
        protected Greeting createInstance(Context context) {
            return new Greeting(getInstanceForConstructor(0, context));
        }
    }

    @Test
    void buildResolvesConstructorDependency() {
        Context context = new StubContext(Map.of(Key.of(String.class), (Provider<String>) () -> "hi"));
        assertThat(new GreetingFactory())
                .returns(Greeting.class, Factory::type)
                .returns(List.of(Key.of(Greeting.class)), Factory::exposedKeys)
                .extracting(f -> f.build(context))
                .isEqualTo(new Greeting("hi"));
    }

    @Test
    @SuppressWarnings("NullAway")
    void constructorRejectsNullType() {
        assertThatNullPointerException().isThrownBy(() -> new Factory<Object>(null) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        });
    }
}
