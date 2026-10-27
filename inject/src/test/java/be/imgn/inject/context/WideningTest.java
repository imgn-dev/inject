package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WideningTest {
    private static final String LINE = System.lineSeparator();

    @Test
    void aSingletonCannotDependDirectlyOnAChildLocalBean() {
        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(() -> root(new SelectionFactory(), new ClipboardFactory(Singleton.class, false)))
                .withMessage("Dependencies on narrower scopes:" + LINE
                        + "  " + Key.of(Clipboard.class) + " is @Singleton and depends directly on "
                        + Key.of(Selection.class) + " (constructor parameter selection), which is @ViewScoped. A"
                        + " @Singleton bean is built in the root injector, where no @ViewScoped instance exists. Make"
                        + " it @ViewScoped or unscoped.");
    }

    @Test
    void aSingletonMayDependOnAChildLocalBeanThroughAProvider() {
        DefaultContext root = root(new SelectionFactory(), new ClipboardFactory(Singleton.class, true));

        assertThat(root.getBeanDefinition(Key.of(Clipboard.class))).isPresent();
    }

    @Test
    void anUnscopedBeanMayDependDirectlyOnAChildLocalBean() {
        DefaultContext root = root(new SelectionFactory(), new ClipboardFactory(null, false));
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);

        assertThat(view.getProvider(Key.of(Clipboard.class)).get().selection())
                .isSameAs(view.getProvider(Key.of(Selection.class)).get());
    }

    @Test
    void aNarrowerBeanMayDependDirectlyOnAWiderOne() {
        DefaultContext root = root(new SelectionFactory(), new ClipboardFactory(WindowScoped.class, false));
        ChildContext dialog =
                new ChildContext(new ChildContext(root, Map.of(), ViewScoped.class), Map.of(), WindowScoped.class);

        assertThat(dialog.getProvider(Key.of(Clipboard.class)).get().selection())
                .isNotNull();
    }

    @Test
    void aHostMissingTheScopeOfADirectDependencyIsNotBuilt() {
        DefaultContext root = root(new SelectionFactory(), new ClipboardFactory(WindowScoped.class, false));

        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(() -> new ChildContext(root, Map.of(), WindowScoped.class))
                .withMessage("Cannot build the @WindowScoped injector:" + LINE
                        + "  " + Key.of(Clipboard.class) + " (@WindowScoped) depends directly on "
                        + Key.of(Selection.class) + " (constructor parameter selection), which is @ViewScoped, and no"
                        + " injector above this one hosts @ViewScoped. Build this injector below one that hosts"
                        + " @ViewScoped, or make the bean @ViewScoped.");
    }

    @Test
    void aHostMayLackTheScopeOfADependencyItReachesThroughAProvider() {
        DefaultContext root = root(new SelectionFactory(), new ClipboardFactory(WindowScoped.class, true));
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);

        assertThat(window.getProvider(Key.of(Clipboard.class)).get()).isNotNull();
    }

    private static DefaultContext root(Factory<?>... factories) {
        return new DefaultContext(List.of(factories), List.of(new SingletonScope()));
    }

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface WindowScoped {}

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface ViewScoped {}

    static final class Selection {}

    record Clipboard(@Nullable Selection selection) {}

    /** {@code @ViewScoped Selection()}. */
    static final class SelectionFactory extends Factory<Selection> {
        SelectionFactory() {
            super(Selection.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(ViewScoped.class);
        }

        @Override
        protected Selection createInstance(Context context) {
            return new Selection();
        }
    }

    /** {@code Clipboard(Selection)} or {@code Clipboard(Provider<Selection>)}, with the given scope. */
    static final class ClipboardFactory extends Factory<Clipboard> {
        private final @Nullable Class<? extends Annotation> scope;
        private final boolean throughProvider;

        ClipboardFactory(@Nullable Class<? extends Annotation> scope, boolean throughProvider) {
            super(Clipboard.class);
            this.scope = scope;
            this.throughProvider = throughProvider;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.ofNullable(scope);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this,
                    List.of(new Parameter("selection", Selection.class, throughProvider)),
                    AnnotationMetadata.empty()));
        }

        @Override
        protected Clipboard createInstance(Context context) {
            if (throughProvider) {
                Provider<Selection> unused = this.getProviderForConstructor(0, Selection.class, context);
                return new Clipboard(null);
            }
            return new Clipboard(this.<Selection>getInstanceForConstructor(0, Selection.class, context));
        }
    }
}
