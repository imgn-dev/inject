package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.inject.InjectionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.Types;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactoryTest {
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

    @Test
    void resolvedTypeMustBeTheErasureOfTheDeclaredType() {
        Context context = new StubContext(Map.of(Key.of(String.class), (Provider<String>) () -> "hi"));
        assertThatThrownBy(() -> new ProbeFactory(String.class, Integer.class).build(context))
                .isInstanceOf(InjectionException.class)
                .hasMessage("java.lang.Object constructor parameter value is declared as java.lang.String,"
                        + " but is resolved as java.lang.Integer.");
    }

    @Test
    void genericDeclaredTypeIsResolvedAsItsRawClass() {
        Set<String> names = Set.of("a");
        Type setOfString = Types.setOf(String.class);
        Context context = new StubContext(Map.of(Key.of(setOfString), (Provider<Set<String>>) () -> names));
        assertThat(new ProbeFactory(setOfString, Set.class).build(context)).isSameAs(names);
    }

    @Test
    void resolvedValueMustBeAnInstanceOfTheType() {
        Context context = new StubContext(Map.of(Key.of(String.class), (Provider<Integer>) () -> 42));
        assertThatThrownBy(() -> new ProbeFactory(String.class, String.class).build(context))
                .isInstanceOf(InjectionException.class)
                .hasMessage("java.lang.Object constructor parameter value expects java.lang.String,"
                        + " but was given java.lang.Integer.");
    }

    @Test
    void primitiveTypeAcceptsItsWrapper() {
        Context context = new StubContext(Map.of(Key.of(int.class), (Provider<Integer>) () -> 7));
        assertThat(new ProbeFactory(int.class, int.class).build(context)).isEqualTo(7);
    }

    @Test
    // A provider breaking its non-null contract is the point of the test.
    @SuppressWarnings("NullAway")
    void primitiveTypeRejectsNull() {
        Context context = new StubContext(Map.of(Key.of(int.class), (Provider<Integer>) () -> null));
        assertThatThrownBy(() -> new ProbeFactory(int.class, int.class).build(context))
                .isInstanceOf(InjectionException.class)
                .hasMessage("java.lang.Object constructor parameter value expects int, but was given null.");
    }

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

    /** Resolves one constructor parameter declared as {@code declared}, asking for it as {@code resolvedAs}. */
    static final class ProbeFactory extends Factory<Object> {
        private final Type declared;
        private final Class<?> resolvedAs;

        ProbeFactory(Type declared, Class<?> resolvedAs) {
            super(Object.class);
            this.declared = declared;
            this.resolvedAs = resolvedAs;
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", declared)), AnnotationMetadata.empty()));
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Object createInstance(Context context) {
            return getInstanceForConstructor(0, (Class<? super Object>) resolvedAs, context);
        }
    }

    /** Hand-written factory standing in for generated output. */
    static final class GreetingFactory extends Factory<Greeting> {
        GreetingFactory() {
            super(Greeting.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("message", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Greeting createInstance(Context context) {
            return new Greeting(this.<String>getInstanceForConstructor(0, String.class, context));
        }
    }
}
