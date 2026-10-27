package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.inject.ConstructionListener;
import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import be.imgn.inject.Types;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ConstructionListenerTest {
    private final List<Reported> reported = new ArrayList<>();
    private final Injector rootInjector = Injector.builder().build();

    @Test
    void aSingletonIsReportedWithTheRootInjector() {
        DefaultContext root = root(new PartFactory("singleton", Singleton.class));

        Part part = resolve(root, "singleton");

        assertThat(reported).containsExactly(new Reported(part, rootInjector));
    }

    @Test
    void anUnscopedBeanIsReportedWithTheChildThatBuiltIt() {
        DefaultContext root = root(new PartFactory("unscoped", null));
        Injector childInjector = Injector.builder().build();
        ChildContext child = attach(new ChildContext(root, Map.of()), childInjector);

        Part part = resolve(child, "unscoped");

        assertThat(reported).containsExactly(new Reported(part, childInjector));
    }

    @Test
    void aChildLocalBeanIsReportedWithItsHost() {
        DefaultContext root = root(new PartFactory("view", ViewScoped.class));
        Injector hostInjector = Injector.builder().build();
        ChildContext view = attach(new ChildContext(root, Map.of(), ViewScoped.class), hostInjector);
        ChildContext below =
                attach(new ChildContext(view, Map.of()), Injector.builder().build());

        Part part = resolve(below, "view");
        resolve(view, "view");

        assertThat(reported).containsExactly(new Reported(part, hostInjector));
    }

    @Test
    void theListenersAndTheirDependenciesAreNotReported() {
        DefaultContext root = root(new PartFactory("dependency", Singleton.class));
        Part dependency = resolve(root, "dependency");

        assertThat(reported).isEmpty();
        assertThat(resolve(root, "dependency")).isSameAs(dependency);
    }

    @Test
    void aFailingListenerFailsTheConstructionWhichIsNotCached() {
        DefaultContext root = new DefaultContext(
                List.of(new FailingListenerFactory(), new PartFactory("view", ViewScoped.class)),
                List.of(new SingletonScope()));
        root.setInjector(rootInjector);
        ChildContext view = attach(
                new ChildContext(root, Map.of(), ViewScoped.class),
                Injector.builder().build());

        assertThatIllegalStateException()
                .isThrownBy(() -> resolve(view, "view"))
                .withMessage("rejected");
        assertThatIllegalStateException()
                .isThrownBy(() -> resolve(view, "view"))
                .withMessage("rejected");
    }

    @Test
    void boundInstancesAreNotReported() {
        DefaultContext root = root();
        ChildContext child = attach(
                new ChildContext(root, Map.of(Key.of(String.class), () -> "bound")),
                Injector.builder().build());

        assertThat(child.getProvider(Key.of(String.class)).get()).isEqualTo("bound");
        assertThat(reported).isEmpty();
    }

    /** {@return a root over {@code factories} and a listener recording into {@link #reported}} */
    private DefaultContext root(Factory<?>... factories) {
        List<Factory<?>> all = new ArrayList<>(List.of(factories));
        all.add(new RecordingListenerFactory(reported));
        DefaultContext root = new DefaultContext(all, List.of(new SingletonScope()));
        root.setInjector(rootInjector);
        return root;
    }

    private static ChildContext attach(ChildContext context, Injector injector) {
        context.setInjector(injector);
        return context;
    }

    private static Part resolve(Context context, String name) {
        return context.getProvider(Key.of(Part.class, Names.named(name))).get();
    }

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface ViewScoped {}

    record Part(String name) {}

    record Reported(Object instance, Injector injector) {}

    /** A {@code @Named(name) Part} with the given scope. */
    static final class PartFactory extends Factory<Part> {
        private final String name;
        private final @Nullable Class<? extends Annotation> scope;

        PartFactory(String name, @Nullable Class<? extends Annotation> scope) {
            super(Part.class);
            this.name = name;
            this.scope = scope;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.ofNullable(scope);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Part.class, Names.named(name)));
        }

        @Override
        protected Part createInstance(Context context) {
            return new Part(name);
        }
    }

    /**
     * {@code @Singleton @IntoSet(ConstructionListener.class)}, depending on {@code @Named("dependency") Part} when one
     * is bound.
     */
    static final class RecordingListenerFactory extends Factory<ConstructionListener> {
        private final List<Reported> reported;

        RecordingListenerFactory(List<Reported> reported) {
            super(ConstructionListener.class);
            this.reported = reported;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(new Contribution(Key.of(Types.setOf(ConstructionListener.class)), null));
        }

        @Override
        protected ConstructionListener createInstance(Context context) {
            Key<Part> dependency = Key.of(Part.class, Names.named("dependency"));
            if (context.getBeanDefinition(dependency).isPresent()) {
                context.getProvider(dependency).get();
            }
            return (instance, injector) -> reported.add(new Reported(instance, injector));
        }
    }

    /** A listener rejecting every child-local {@code Part}. */
    static final class FailingListenerFactory extends Factory<ConstructionListener> {
        FailingListenerFactory() {
            super(ConstructionListener.class);
        }

        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(new Contribution(Key.of(Types.setOf(ConstructionListener.class)), null));
        }

        @Override
        protected ConstructionListener createInstance(Context context) {
            return (instance, injector) -> {
                if (instance instanceof Part) {
                    throw new IllegalStateException("rejected");
                }
            };
        }
    }
}
