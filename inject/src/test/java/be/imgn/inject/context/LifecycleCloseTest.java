package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Singleton;

class LifecycleCloseTest {

    /** A named component recording its destruction into a shared log. */
    record Component(String name) {}

    /** Singleton factory whose preDestroy logs the name; optionally throws. */
    static final class ComponentFactory extends Factory<Component> {
        private final String name;
        private final List<String> destroyed;
        private final boolean throwOnDestroy;

        ComponentFactory(String name, List<String> destroyed, boolean throwOnDestroy) {
            super(Component.class);
            this.name = name;
            this.destroyed = destroyed;
            this.throwOnDestroy = throwOnDestroy;
        }

        @Override
        protected Component createInstance(Context context) {
            return new Component(name);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Component.class, Names.named(name)));
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Component instance) {
            destroyed.add(instance.name());
            if (throwOnDestroy) {
                throw new IllegalStateException("boom-" + instance.name());
            }
        }
    }

    @Test
    void closeInvokesPreDestroyLifoForCreatedSingletons() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(new ComponentFactory("a", destroyed, false), new ComponentFactory("b", destroyed, false)),
                List.of(new SingletonScope()));
        // create both, in order a then b
        context.getProvider(Key.of(Component.class, Names.named("a"))).get();
        context.getProvider(Key.of(Component.class, Names.named("b"))).get();
        context.close();
        assertThat(destroyed).containsExactly("b", "a"); // LIFO
    }

    @Test
    void uncreatedSingletonIsNotDestroyed() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context =
                new DefaultContext(List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        context.close(); // never resolved
        assertThat(destroyed).isEmpty();
    }

    @Test
    void closeCompletesEvenWhenACallbackThrows() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(new ComponentFactory("a", destroyed, false), new ComponentFactory("b", destroyed, true)),
                List.of(new SingletonScope()));
        context.getProvider(Key.of(Component.class, Names.named("a"))).get();
        context.getProvider(Key.of(Component.class, Names.named("b"))).get();
        assertThatThrownBy(context::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom-b");
        assertThat(destroyed).containsExactly("b", "a"); // b threw, a still destroyed
    }

    @Test
    void doubleCloseIsANoOp() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context =
                new DefaultContext(List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        context.getProvider(Key.of(Component.class, Names.named("a"))).get();
        context.close();
        context.close();
        assertThat(destroyed).containsExactly("a"); // once
    }

    @Test
    void resolutionAfterCloseThrows() {
        DefaultContext context = new DefaultContext(List.of(), List.of());
        context.close();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
    }
}
