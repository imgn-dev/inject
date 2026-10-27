package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AmbiguousKeyTest {
    private static final String LINE = System.lineSeparator();

    @Test
    void aKeySeveralBeansHaveThroughTheirSupertypesIsLeftUnbound() {
        DefaultContext root = root(editor(), preview());

        assertThat(root.getProvider(Key.of(Editor.class)).get()).isInstanceOf(Editor.class);
        assertThat(root.getProvider(Key.of(Preview.class)).get()).isInstanceOf(Preview.class);
        assertThat(root.getBeanDefinition(Key.of(View.class))).isEmpty();
    }

    @Test
    void resolvingAnAmbiguousKeyNamesTheBeansThatHaveIt() {
        DefaultContext root = root(editor(), preview());

        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> root.getProvider(Key.of(View.class)))
                .withMessage("No unique binding for " + Key.of(View.class) + ": implemented by " + Key.of(Editor.class)
                        + ", " + Key.of(Preview.class) + ". Use @ExposeAs, or a qualifier, to choose which one it"
                        + " binds.");
    }

    @Test
    void dependingOnAnAmbiguousKeyIsAnErrorAtCreation() {
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> root(editor(), preview(), new FrameFactory()))
                .withMessage("Unsatisfied dependencies:" + LINE
                        + "  No unique binding for " + Key.of(View.class) + " (required by " + Key.of(Frame.class)
                        + " constructor parameter view): implemented by " + Key.of(Editor.class) + ", "
                        + Key.of(Preview.class) + ". Use @ExposeAs, or a qualifier, to choose which one it binds.");
    }

    @Test
    void aSingleBeanWithTheKeyBindsIt() {
        DefaultContext root = root(editor(), new FrameFactory());

        assertThat(root.getProvider(Key.of(Frame.class)).get().view()).isInstanceOf(Editor.class);
    }

    @Test
    void anExplicitBindingWinsOverSupertypeClaims() {
        DefaultContext root = root(editor(), preview(), new ExplicitViewFactory());

        assertThat(root.getProvider(Key.of(View.class)).get()).isInstanceOf(Console.class);
    }

    @Test
    void twoExplicitBindingsAreStillADuplicate() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> root(new ExplicitViewFactory(), new ExplicitViewFactory()))
                .withMessage("Duplicate binding for " + Key.of(View.class));
    }

    @Test
    void anOverrideBindsAnAmbiguousKey() {
        Console console = new Console();
        DefaultContext root = new DefaultContext(
                List.of(editor(), preview(), new FrameFactory()), List.of(), Map.of(Key.of(View.class), () -> console));

        assertThat(root.getProvider(Key.of(Frame.class)).get().view()).isSameAs(console);
    }

    private static Factory<Editor> editor() {
        return new ViewFactory<>(Editor.class, Editor::new);
    }

    private static Factory<Preview> preview() {
        return new ViewFactory<>(Preview.class, Preview::new);
    }

    private static DefaultContext root(Factory<?>... factories) {
        return new DefaultContext(List.of(factories), List.of());
    }

    interface View {}

    static final class Editor implements View {}

    static final class Preview implements View {}

    static final class Console implements View {}

    record Frame(View view) {}

    /** A bean exposed under its class, and under {@code View} through its supertypes. */
    static final class ViewFactory<T extends View> extends Factory<T> {
        private final Supplier<T> constructor;

        ViewFactory(Class<T> type, Supplier<T> constructor) {
            super(type);
            this.constructor = constructor;
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(type()), Key.of(View.class));
        }

        @Override
        protected List<Key<?>> supertypeKeys() {
            return List.of(Key.of(View.class));
        }

        @Override
        protected T createInstance(Context context) {
            return constructor.get();
        }
    }

    /** A {@code Console} exposed explicitly as {@code View}, as {@code @ExposeAs(View.class)} does. */
    static final class ExplicitViewFactory extends Factory<Console> {
        ExplicitViewFactory() {
            super(Console.class);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(View.class));
        }

        @Override
        protected Console createInstance(Context context) {
            return new Console();
        }
    }

    /** {@code Frame(View)}. */
    static final class FrameFactory extends Factory<Frame> {
        FrameFactory() {
            super(Frame.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("view", View.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Frame createInstance(Context context) {
            return new Frame(this.<View>getInstanceForConstructor(0, View.class, context));
        }
    }
}
