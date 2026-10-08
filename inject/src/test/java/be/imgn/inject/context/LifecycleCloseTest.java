package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Singleton;
import be.imgn.inject.Types;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LifecycleCloseTest {
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
        // LIFO
        assertThat(destroyed).containsExactly("b", "a");
    }

    @Test
    void uncreatedSingletonIsNotDestroyed() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context =
                new DefaultContext(List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        // Never resolved
        context.close();
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
        // "b" threw, "a" was still destroyed
        assertThat(destroyed).containsExactly("b", "a");
    }

    @Test
    void doubleCloseIsANoOp() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context =
                new DefaultContext(List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        context.getProvider(Key.of(Component.class, Names.named("a"))).get();
        context.close();
        context.close();
        // Once
        assertThat(destroyed).containsExactly("a");
    }

    @Test
    void resolutionAfterCloseThrows() {
        DefaultContext context = new DefaultContext(List.of(), List.of());
        context.close();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void singletonSetContributorIsDestroyedOnClose() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context =
                new DefaultContext(List.of(new ElementContribFactory(destroyed)), List.of(new SingletonScope()));
        // Force creation of the singleton contributor
        context.getProvider(Key.of(Types.setOf(Element.class))).get();
        context.close();
        assertThat(destroyed).containsExactly("element");
    }

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

    /** A marker interface contributed into a {@code Set}. */
    interface Element {}

    /** The bean implementing {@link Element}. */
    record ElementImpl() implements Element {}

    /** Singleton {@code @IntoSet} contributor whose preDestroy logs into a shared list. */
    static final class ElementContribFactory extends Factory<Element> {
        private final List<String> destroyed;

        ElementContribFactory(List<String> destroyed) {
            super(Element.class);
            this.destroyed = destroyed;
        }

        @Override
        protected Element createInstance(Context context) {
            return new ElementImpl();
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of();
        }

        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(new Contribution(Key.of(Types.setOf(Element.class)), null));
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
        protected void preDestroy(Element instance) {
            destroyed.add("element");
        }
    }
}
