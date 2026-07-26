package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;

class ChildContextTest {

    /** An unscoped bean holding the String dependency it was built with. */
    record Holder(String value) {}

    /** Unscoped factory: Holder(String) — the rebuild path must let children re-resolve the String. */
    static final class HolderFactory extends Factory<Holder> {
        HolderFactory() {
            super(Holder.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Holder createInstance(Context context) {
            return new Holder(getInstanceForConstructor(0, context));
        }
    }

    /** Singleton flavor of the same shape: must anchor to the parent. */
    static final class SingletonHolderFactory extends Factory<Holder> {
        SingletonHolderFactory() {
            super(Holder.class);
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return AnnotationMetadata.of(
                    AnnotationValue.of(Singleton.class, Map.of(), Set.of(ScopingAnnotation.class)));
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Holder createInstance(Context context) {
            return new Holder(getInstanceForConstructor(0, context));
        }
    }

    /** Leaf String bound in the parent. */
    static final class ParentStringFactory extends Factory<String> {
        ParentStringFactory() {
            super(String.class);
        }

        @Override
        protected String createInstance(Context context) {
            return "parent";
        }
    }

    private static ChildContext child(Context parent, Map<Key<?>, Provider<?>> bindings) {
        ChildContext context = new ChildContext(parent, bindings);
        context.setInjector(Injector.builder().build());
        return context;
    }

    @Test
    void localBindingWins() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("child");
        assertThat(parent.getProvider(Key.of(String.class)).get()).isEqualTo("parent");
    }

    @Test
    void unscopedParentBeanRebuildsWithChildBindings() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory(), new HolderFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        Holder viaChild = (Holder) context.getProvider(Key.of(Holder.class)).get();
        Holder viaParent = (Holder) parent.getProvider(Key.of(Holder.class)).get();
        assertThat(viaChild.value()).isEqualTo("child");
        assertThat(viaParent.value()).isEqualTo("parent");
    }

    @Test
    void scopedParentBeanAnchorsToParent() {
        DefaultContext parent = new DefaultContext(
                List.of(new ParentStringFactory(), new SingletonHolderFactory()), List.of(new SingletonScope()));
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        Holder viaChild = (Holder) context.getProvider(Key.of(Holder.class)).get();
        assertThat(viaChild.value()).isEqualTo("parent");
        assertThat(viaChild).isSameAs(parent.getProvider(Key.of(Holder.class)).get());
    }

    @Test
    void missReportsAfterChainMiss() {
        DefaultContext parent = new DefaultContext(List.of(), List.of());
        ChildContext context = child(parent, Map.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get());
    }

    @Test
    void cycleThroughChildRebuildIsReported() {
        // Holder depends on String; bind String through a provider that resolves Holder -> cycle via the child.
        DefaultContext parent = new DefaultContext(List.of(new HolderFactory()), List.of());
        ChildContext[] hole = new ChildContext[1];
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> {
            hole[0].getProvider(Key.of(Holder.class)).get();
            return "unreachable";
        }));
        hole[0] = context;
        assertThatExceptionOfType(CircularDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Holder.class)).get());
    }

    @Test
    void injectorKeyResolvesTheAttachedInjector() {
        DefaultContext parent = new DefaultContext(List.of(), List.of());
        ChildContext context = new ChildContext(parent, Map.of());
        Injector attached = Injector.builder().build();
        context.setInjector(attached);
        assertThat(context.getProvider(Key.of(Injector.class)).get()).isSameAs(attached);
        assertThatIllegalStateException().isThrownBy(() -> context.setInjector(attached));
    }

    @Test
    void nestedChildSeesNearestAncestorShadowOfDiscoveredBeans() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory(), new HolderFactory()), List.of());
        ChildContext mid = child(parent, Map.of(Key.of(String.class), () -> "mid"));
        ChildContext grand = child(mid, Map.of());
        assertThat(grand.getProvider(Key.of(String.class)).get()).isEqualTo("mid");
        assertThat(((Holder) grand.getProvider(Key.of(Holder.class)).get()).value())
                .isEqualTo("mid");
    }

    @Test
    void introspectionDelegatesToParent() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(Integer.class), () -> 1));
        assertThat(context.getBeanDefinition(Key.of(String.class))).isPresent();
        assertThat(context.getBeanDefinition(Key.of(Integer.class))).isEmpty();
    }

    @Test
    void childCloseDoesNotCloseTheParent() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of());
        context.close();
        // child closed: its own resolution fails
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
        // parent still open
        assertThat(parent.getProvider(Key.of(String.class)).get()).isEqualTo("parent");
    }
}
