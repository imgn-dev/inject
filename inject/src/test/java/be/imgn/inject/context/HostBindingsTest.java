package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.Key;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HostBindingsTest {
    private static final String LINE = System.lineSeparator();

    @Test
    void theRootAcceptsChildLocalDependenciesItDoesNotBind() {
        DefaultContext root = root(new ToolbarFactory(), new LabelFactory());

        assertThat(root.getBeanDefinition(Key.of(Toolbar.class))).isPresent();
    }

    @Test
    void theRootStillRejectsAnUnscopedBeanWhoseDependencyNothingBinds() {
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> root(new UnscopedToolbarFactory()))
                .withMessageContaining("No binding for " + Key.of(Window.class));
    }

    @Test
    void aHostThatBindsWhatItsBeansNeedIsBuilt() {
        DefaultContext root = root(new ToolbarFactory());
        Window frame = new Window("frame");
        ChildContext window = new ChildContext(root, Map.of(Key.of(Window.class), () -> frame), WindowScoped.class);

        assertThat(window.getProvider(Key.of(Toolbar.class)).get()).isEqualTo(new Toolbar(frame));
    }

    @Test
    void aHostMissingABindingItsBeansNeedIsNotBuilt() {
        DefaultContext root = root(new ToolbarFactory());

        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> new ChildContext(root, Map.of(), WindowScoped.class))
                .withMessage("Cannot build the @WindowScoped injector:" + LINE
                        + "  " + Key.of(Toolbar.class) + " (@WindowScoped) depends on " + Key.of(Window.class)
                        + " (constructor parameter window), which neither this injector nor its parents bind." + LINE
                        + "Bind each on this injector's builder, or on one of its parents.");
    }

    @Test
    void aHostFindsWhatItsBeansNeedInItsParents() {
        DefaultContext root = root(new ToolbarFactory(), new LabelFactory());
        Window frame = new Window("frame");
        ChildContext window = new ChildContext(
                root,
                Map.of(Key.of(Window.class), () -> frame, Key.of(String.class), () -> "window"),
                WindowScoped.class);
        ChildContext view = new ChildContext(window, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);

        assertThat(view.getProvider(Key.of(Label.class)).get()).isEqualTo(new Label(frame, "view"));
    }

    @Test
    void aKeyBoundByTheRootNeedsNoBindingInTheHost() {
        DefaultContext root = new DefaultContext(
                List.of(new LabelFactory()),
                List.of(new SingletonScope()),
                Map.of(Key.of(Window.class), () -> new Window("root"), Key.of(String.class), () -> "root"));

        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);

        assertThat(view.getProvider(Key.of(Label.class)).get()).isEqualTo(new Label(new Window("root"), "root"));
    }

    @Test
    void everyMissingBindingIsListed() {
        DefaultContext root = root(new LabelFactory());

        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> new ChildContext(root, Map.of(), ViewScoped.class))
                .withMessageContaining("depends on " + Key.of(Window.class) + " (constructor parameter window)")
                .withMessageContaining("depends on " + Key.of(String.class) + " (constructor parameter text)");
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

    record Window(String name) {}

    record Toolbar(Window window) {}

    record Label(Window window, String text) {}

    /** {@code @WindowScoped Toolbar(Window)}. */
    static class ToolbarFactory extends Factory<Toolbar> {
        ToolbarFactory() {
            super(Toolbar.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(WindowScoped.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("window", Window.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Toolbar createInstance(Context context) {
            return new Toolbar(this.<Window>getInstanceForConstructor(0, Window.class, context));
        }
    }

    /** Unscoped {@code Toolbar(Window)}. */
    static final class UnscopedToolbarFactory extends ToolbarFactory {
        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.empty();
        }
    }

    /** {@code @ViewScoped Label(Window, String)}. */
    static final class LabelFactory extends Factory<Label> {
        LabelFactory() {
            super(Label.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(ViewScoped.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this,
                    List.of(new Parameter("window", Window.class), new Parameter("text", String.class)),
                    AnnotationMetadata.empty()));
        }

        @Override
        protected Label createInstance(Context context) {
            return new Label(
                    this.<Window>getInstanceForConstructor(0, Window.class, context),
                    this.<String>getInstanceForConstructor(1, String.class, context));
        }
    }
}
