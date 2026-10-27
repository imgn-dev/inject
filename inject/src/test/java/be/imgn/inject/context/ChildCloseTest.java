package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopingAnnotation;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChildCloseTest {
    private final List<String> destroyed = new ArrayList<>();

    @Test
    void closingAHostDestroysItsInstancesInReverseCreationOrder() {
        DefaultContext root = root(part("a", ViewScoped.class, false), part("b", ViewScoped.class, false));
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);
        resolve(view, "a");
        resolve(view, "b");
        resolve(view, "a");

        view.close();

        assertThat(destroyed).containsExactly("b", "a");
    }

    @Test
    void anInstanceNeverCreatedIsNotDestroyed() {
        DefaultContext root = root(part("a", ViewScoped.class, false));
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);

        view.close();

        assertThat(destroyed).isEmpty();
    }

    @Test
    void closingAHostClosesItsChildrenFirst() {
        DefaultContext root = root(part("window", WindowScoped.class, false), part("view", ViewScoped.class, false));
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);
        ChildContext view = new ChildContext(window, Map.of(), ViewScoped.class);
        resolve(window, "window");
        resolve(view, "view");

        window.close();

        assertThat(destroyed).containsExactly("view", "window");
        assertThatIllegalStateException().isThrownBy(() -> view.getProvider(Key.of(String.class)));
    }

    @Test
    void closingTheRootClosesItsLiveHostsFirst() {
        DefaultContext root = root(part("view", ViewScoped.class, false));
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);
        resolve(view, "view");

        root.close();

        assertThat(destroyed).containsExactly("view");
        assertThatIllegalStateException().isThrownBy(() -> view.getProvider(Key.of(String.class)));
    }

    @Test
    void aClosedHostIsNotClosedAgainWithItsParent() {
        DefaultContext root = root(part("view", ViewScoped.class, false));
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);
        resolve(view, "view");

        view.close();
        root.close();

        assertThat(destroyed).containsExactly("view");
    }

    @Test
    void closingAHostDoesNotCloseItsParent() {
        DefaultContext root = root(part("window", WindowScoped.class, false), part("view", ViewScoped.class, false));
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);
        ChildContext view = new ChildContext(window, Map.of(), ViewScoped.class);
        resolve(window, "window");

        view.close();

        assertThat(destroyed).isEmpty();
        assertThat(resolve(window, "window")).isEqualTo(new Part("window"));
    }

    @Test
    void everyInstanceAndChildIsClosedEvenWhenSomeFail() {
        DefaultContext root = root(
                part("window", WindowScoped.class, false),
                part("a", ViewScoped.class, true),
                part("b", ViewScoped.class, false),
                part("c", ViewScoped.class, true));
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);
        ChildContext view = new ChildContext(window, Map.of(), ViewScoped.class);
        resolve(window, "window");
        resolve(view, "a");
        resolve(view, "b");
        resolve(view, "c");

        assertThatThrownBy(window::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom-c")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("boom-a"));
        assertThat(destroyed).containsExactly("c", "b", "a", "window");
    }

    @Test
    void aClosedHostRejectsResolutionThroughItsProvidersToo() {
        DefaultContext root = root(part("view", ViewScoped.class, false));
        ChildContext view = new ChildContext(root, Map.of(Key.of(String.class), () -> "own"), ViewScoped.class);
        Provider<String> own = view.getProvider(Key.of(String.class));
        Provider<Part> hosted = view.getProvider(Key.of(Part.class, Names.named("view")));

        view.close();

        assertThatIllegalStateException()
                .isThrownBy(own::get)
                .withMessage("This @ViewScoped injector is closed: " + Key.of(String.class)
                        + " can no longer be resolved through it.");
        assertThatIllegalStateException().isThrownBy(hosted::get);
        assertThatIllegalStateException()
                .isThrownBy(() -> view.getProvider(Key.of(String.class)))
                .withMessage("This @ViewScoped injector is closed: " + Key.of(String.class)
                        + " can no longer be resolved through it.");
    }

    @Test
    void aClosedPlainChildNamesNoScope() {
        DefaultContext root = root();
        ChildContext child = new ChildContext(root, Map.of(Key.of(String.class), () -> "own"));

        child.close();

        assertThatIllegalStateException()
                .isThrownBy(() -> child.getProvider(Key.of(String.class)))
                .withMessage(
                        "This injector is closed: " + Key.of(String.class) + " can no longer be resolved through it.");
    }

    @Test
    void noChildCanBeBuiltUnderAClosedHost() {
        DefaultContext root = root(part("window", WindowScoped.class, false));
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);
        resolve(window, "window");
        window.close();

        assertThatIllegalStateException()
                .isThrownBy(() -> new ChildContext(window, Map.of(), ViewScoped.class))
                .withMessage("This @WindowScoped injector is closed: no child injector can be built under it.");
        assertThatIllegalStateException()
                .isThrownBy(() -> new ChildContext(window, Map.of()))
                .withMessage("This @WindowScoped injector is closed: no child injector can be built under it.");
        assertThat(destroyed).containsExactly("window");
    }

    @Test
    void noChildCanBeBuiltUnderAClosedRoot() {
        DefaultContext root = root();
        root.close();

        assertThatIllegalStateException()
                .isThrownBy(() -> new ChildContext(root, Map.of(), ViewScoped.class))
                .withMessage("This injector is closed: no child injector can be built under it.");
    }

    private DefaultContext root(Factory<?>... factories) {
        return new DefaultContext(List.of(factories), List.of(new SingletonScope()));
    }

    private Factory<Part> part(String name, Class<? extends Annotation> scope, boolean failing) {
        return new PartFactory(name, scope, destroyed, failing);
    }

    private static Part resolve(Context context, String name) {
        return context.getProvider(Key.of(Part.class, Names.named(name))).get();
    }

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface WindowScoped {}

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface ViewScoped {}

    record Part(String name) {}

    /** A child-local {@code @Named(name) Part} that records its destruction, and may fail it. */
    static final class PartFactory extends Factory<Part> {
        private final String name;
        private final Class<? extends Annotation> scope;
        private final List<String> destroyed;
        private final boolean failing;

        PartFactory(String name, Class<? extends Annotation> scope, List<String> destroyed, boolean failing) {
            super(Part.class);
            this.name = name;
            this.scope = scope;
            this.destroyed = destroyed;
            this.failing = failing;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(scope);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Part.class, Names.named(name)));
        }

        @Override
        protected Part createInstance(Context context) {
            return new Part(name);
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Part instance) {
            destroyed.add(instance.name());
            if (failing) {
                throw new IllegalStateException("boom-" + instance.name());
            }
        }
    }
}
