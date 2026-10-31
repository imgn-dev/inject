package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.ConstructionListener;
import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;
import be.imgn.inject.Types;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EagerSingletonTest {
    private final List<String> built = new ArrayList<>();
    private final List<Object> reported = new ArrayList<>();
    private final List<String> destroyed = new ArrayList<>();

    @Test
    void anEagerSingletonIsBuiltAndReportedWhenTheInjectorIsAttached() {
        DefaultContext root =
                attach(new DefaultContext(withListener(part("eager").eager()), List.of(new SingletonScope())));

        assertThat(built).containsExactly("eager");
        Part part = resolve(root, "eager");
        assertThat(reported).containsExactly(part);
        assertThat(built).containsExactly("eager");
    }

    @Test
    void aSingletonThatIsNotEagerIsNotBuilt() {
        attach(new DefaultContext(withListener(part("lazy")), List.of(new SingletonScope())));

        assertThat(built).isEmpty();
    }

    @Test
    void theDependenciesOfAnEagerSingletonAreReported() {
        DefaultContext root = attach(new DefaultContext(
                withListener(
                        new DependencyFactory(built, destroyed),
                        part("eager").eager().dependingOn()),
                List.of(new SingletonScope())));

        assertThat(reported)
                .containsExactly(root.getProvider(Key.of(Dependency.class)).get(), resolve(root, "eager"));
    }

    @Test
    void anEagerBeanWhoseConditionFailsIsNotBuilt() {
        AnnotationValue condition = AnnotationValue.of(
                ConditionalOnProperty.class,
                Map.of("value", "feature", "havingValue", "on", "matchIfMissing", false),
                Set.of(ConditioningAnnotation.class));

        attach(new DefaultContext(
                withListener(part("gated").eager().conditional(condition)),
                List.of(new SingletonScope()),
                List.of(new OnPropertyCondition()),
                Map.of()));

        assertThat(built).isEmpty();
    }

    @Test
    void aDefaultThatLostItsKeyIsNotBuilt() {
        attach(new DefaultContext(
                withListener(part("shared").eager().fallback(), part("shared")), List.of(new SingletonScope())));

        assertThat(built).isEmpty();
    }

    @Test
    void anEagerBeanEveryKeyOfWhichIsOverriddenIsNotBuilt() {
        Map<Key<?>, Provider<?>> overrides =
                Map.of(Key.of(Part.class, Names.named("overridden")), () -> new Part("bound"));

        attach(new DefaultContext(withListener(part("overridden").eager()), List.of(new SingletonScope()), overrides));

        assertThat(built).isEmpty();
    }

    @Test
    void anEagerContributorIsBuilt() {
        attach(new DefaultContext(withListener(part("member").eager().contributing()), List.of(new SingletonScope())));

        assertThat(built).containsExactly("member");
    }

    @Test
    void aChildBuildsNothingNew() {
        DefaultContext root =
                attach(new DefaultContext(withListener(part("eager").eager()), List.of(new SingletonScope())));

        ChildContext child = new ChildContext(root, Map.of());
        child.setInjector(Injector.builder().build());

        assertThat(built).containsExactly("eager");
        assertThat(resolve(child, "eager")).isSameAs(resolve(root, "eager"));
    }

    @Test
    void aFailingEagerSingletonFailsTheAttachmentAndDestroysWhatWasBuilt() {
        DefaultContext root = new DefaultContext(
                withListener(
                        new DependencyFactory(built, destroyed),
                        part("eager").eager().dependingOn().failing()),
                List.of(new SingletonScope()));

        assertThatIllegalStateException()
                .isThrownBy(() -> root.setInjector(Injector.builder().build()))
                .withMessage("eager failed");
        assertThat(destroyed).containsExactly("dependency");
        assertThatIllegalStateException().isThrownBy(() -> root.getProvider(Key.of(Dependency.class)));
    }

    @Test
    void aFailingListenerFailsTheAttachmentAndDestroysWhatWasBuilt() {
        DefaultContext root = new DefaultContext(
                List.of(new DependencyFactory(built, destroyed), new FailingListenerFactory()),
                List.of(new SingletonScope()));

        assertThatIllegalStateException()
                .isThrownBy(() -> root.setInjector(Injector.builder().build()))
                .withMessage("listener failed");
        assertThat(destroyed).containsExactly("dependency");
    }

    private DefaultContext attach(DefaultContext context) {
        context.setInjector(Injector.builder().build());
        return context;
    }

    private List<Factory<?>> withListener(Factory<?>... factories) {
        List<Factory<?>> all = new ArrayList<>(List.of(factories));
        all.add(new RecordingListenerFactory(reported));
        return all;
    }

    private PartFactory part(String name) {
        return new PartFactory(name, built);
    }

    private static Part resolve(Context context, String name) {
        return context.getProvider(Key.of(Part.class, Names.named(name))).get();
    }

    record Part(String name) {}

    record Dependency() {}

    /** A {@code @Singleton @Named(name) Part} recording each build, configured by its fluent methods. */
    static final class PartFactory extends Factory<Part> {
        private final String name;
        private final List<String> built;
        private boolean eager;
        private boolean fallback;
        private boolean contributing;
        private boolean failing;
        private boolean dependent;
        private AnnotationMetadata metadata = AnnotationMetadata.empty();

        PartFactory(String name, List<String> built) {
            super(Part.class);
            this.name = name;
            this.built = built;
        }

        PartFactory failing() {
            failing = true;
            return this;
        }

        PartFactory eager() {
            eager = true;
            return this;
        }

        PartFactory fallback() {
            fallback = true;
            return this;
        }

        PartFactory contributing() {
            contributing = true;
            return this;
        }

        PartFactory dependingOn() {
            dependent = true;
            return this;
        }

        PartFactory conditional(AnnotationValue condition) {
            metadata = AnnotationMetadata.of(condition);
            return this;
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return contributing ? List.of() : List.of(Key.of(Part.class, Names.named(name)));
        }

        @Override
        protected Optional<Contribution> contribution() {
            return contributing
                    ? Optional.of(new Contribution(Key.of(Types.setOf(Part.class)), null))
                    : Optional.empty();
        }

        @Override
        protected boolean isDefault() {
            return fallback;
        }

        @Override
        protected boolean isEager() {
            return eager;
        }

        @Override
        protected Part createInstance(Context context) {
            if (dependent) {
                context.getProvider(Key.of(Dependency.class)).get();
            }
            if (failing) {
                throw new IllegalStateException(name + " failed");
            }
            built.add(name);
            return new Part(name);
        }
    }

    /** {@code @Singleton @IntoSet(ConstructionListener.class)}, recording every reported instance. */
    static final class RecordingListenerFactory extends Factory<ConstructionListener> {
        private final List<Object> reported;

        RecordingListenerFactory(List<Object> reported) {
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
            return (instance, injector) -> reported.add(instance);
        }
    }

    /** A {@code @Singleton Dependency} recording its build and its destruction. */
    static final class DependencyFactory extends Factory<Dependency> {
        private final List<String> built;
        private final List<String> destroyed;

        DependencyFactory(List<String> built, List<String> destroyed) {
            super(Dependency.class);
            this.built = built;
            this.destroyed = destroyed;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected Dependency createInstance(Context context) {
            built.add("dependency");
            return new Dependency();
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Dependency instance) {
            destroyed.add("dependency");
        }
    }

    /** A listener that builds the {@code Dependency}, then fails. */
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
            context.getProvider(Key.of(Dependency.class)).get();
            throw new IllegalStateException("listener failed");
        }
    }
}
