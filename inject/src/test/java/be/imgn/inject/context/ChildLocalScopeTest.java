package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import be.imgn.inject.Types;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChildLocalScopeTest {

    @Test
    void aHostCreatesTheBeanOnceAndItsDescendantsShareIt() {
        DefaultContext root = root(new SelectionFactory());
        ChildContext view = new ChildContext(root, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);
        ChildContext below = new ChildContext(view, Map.of());

        Selection selection = view.getProvider(Key.of(Selection.class)).get();

        assertThat(selection)
                .isSameAs(view.getProvider(Key.of(Selection.class)).get())
                .isSameAs(below.getProvider(Key.of(Selection.class)).get());
    }

    @Test
    void eachHostHasItsOwnInstance() {
        DefaultContext root = root(new SelectionFactory());
        ChildContext first = new ChildContext(root, Map.of(Key.of(String.class), () -> "first"), ViewScoped.class);
        ChildContext second = new ChildContext(root, Map.of(Key.of(String.class), () -> "second"), ViewScoped.class);

        assertThat(first.getProvider(Key.of(Selection.class)).get())
                .isEqualTo(new Selection("first"))
                .isNotSameAs(second.getProvider(Key.of(Selection.class)).get());
    }

    @Test
    void theBeanIsBuiltThroughItsHostNotThroughTheChildThatResolvesIt() {
        DefaultContext root = root(new SelectionFactory());
        ChildContext view = new ChildContext(root, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);
        ChildContext below = new ChildContext(view, Map.of(Key.of(String.class), () -> "below"));

        assertThat(below.getProvider(Key.of(Selection.class)).get()).isEqualTo(new Selection("view"));
    }

    @Test
    void theNearestHostInTheChainHostsTheBean() {
        DefaultContext root = root(new SelectionFactory());
        ChildContext outer = new ChildContext(root, Map.of(Key.of(String.class), () -> "outer"), ViewScoped.class);
        ChildContext inner = new ChildContext(outer, Map.of(Key.of(String.class), () -> "inner"), ViewScoped.class);

        assertThat(inner.getProvider(Key.of(Selection.class)).get()).isEqualTo(new Selection("inner"));
        assertThat(outer.getProvider(Key.of(Selection.class)).get()).isEqualTo(new Selection("outer"));
    }

    @Test
    void aBeanOfAnEnclosingHostResolvesThroughANestedHost() {
        DefaultContext root = root(new ToolbarFactory(), new SelectionFactory());
        ChildContext window = new ChildContext(root, Map.of(), WindowScoped.class);
        ChildContext view = new ChildContext(window, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);

        assertThat(view.getProvider(Key.of(Toolbar.class)).get())
                .isSameAs(window.getProvider(Key.of(Toolbar.class)).get());
    }

    @Test
    void allTheKeysABeanExposesShareItsInstance() {
        DefaultContext root = root(new ExposedSelectionFactory());
        ChildContext view = new ChildContext(root, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);

        assertThat(view.getProvider(Key.of(Selection.class)).get())
                .isSameAs(view.getProvider(Key.of(Record.class)).get());
    }

    @Test
    void aProviderBreaksACycleBetweenTwoBeansOfTheHost() {
        DefaultContext root = root(new OwnerFactory(), new CommandsFactory());
        ChildContext view = new ChildContext(root, Map.of(), ViewScoped.class);

        Owner owner = view.getProvider(Key.of(Owner.class)).get();

        assertThat(owner.commands().owner().get()).isSameAs(owner);
        assertThat(view.getProvider(Key.of(Commands.class)).get()).isSameAs(owner.commands());
    }

    @Test
    void resolvingWithNoHostInTheChainFailsAndNamesTheScope() {
        DefaultContext root = root(new SelectionFactory());
        ChildContext plain = new ChildContext(root, Map.of(Key.of(String.class), () -> "plain"));
        Provider<Selection> provider = plain.getProvider(Key.of(Selection.class));

        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(provider::get)
                .withMessage("Cannot resolve " + Selection.class.getName() + ": it is @ViewScoped, and no injector in"
                        + " this chain hosts @ViewScoped. Resolve it through an injector created with"
                        + " scopedChildBuilder(ViewScoped.class), or one of its descendants. No Scope service is"
                        + " registered for @ViewScoped either; if it is not meant to live in a child injector,"
                        + " register one.");
    }

    @Test
    void theRootCannotResolveAChildLocalBean() {
        DefaultContext root = root(new SelectionFactory());
        new ChildContext(root, Map.of(Key.of(String.class), () -> "view"), ViewScoped.class);
        Provider<Selection> provider = root.getProvider(Key.of(Selection.class));

        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(provider::get)
                .withMessage("Cannot resolve " + Selection.class.getName() + ": it is @ViewScoped, and no injector in"
                        + " this chain hosts @ViewScoped. Resolve it through an injector created with"
                        + " scopedChildBuilder(ViewScoped.class), or one of its descendants.");
    }

    @Test
    void aScopeWithAScopeServiceCannotBeHosted() {
        DefaultContext root = root();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChildContext(root, Map.of(), Singleton.class))
                .withMessage("@Singleton is handled by the Scope service " + SingletonScope.class.getName()
                        + ", so it is not child-local: child injectors do not cache its instances. Use childBuilder()"
                        + " for a child injector that hosts no scope.");
    }

    @Test
    void scopedChildBuilderBuildsAHostingChild() {
        Injector root = Injector.create();

        assertThat(root.scopedChildBuilder(ViewScoped.class).build().getInstance(Injector.class))
                .isNotSameAs(root);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> root.scopedChildBuilder(Singleton.class).build())
                .withMessageStartingWith("@Singleton is handled by the Scope service");
    }

    @Test
    void aChildLocalBeanCannotContributeToACollection() {
        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(() -> root(new ContributingSelectionFactory()))
                .withMessage(Selection.class.getName() + " is @ViewScoped and contributes to "
                        + Key.of(Types.setOf(Record.class)) + ". A collection is resolved in the root injector, where"
                        + " no @ViewScoped instance exists: remove the scope or the contribution.");
    }

    @Test
    void anAssistedProductMustHaveAChildLocalScope() {
        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(() -> root(new SingletonProductFactory()))
                .withMessage(Key.of(Selection.class) + " is an assisted product and is @Singleton. Only a child-local"
                        + " scope (one without a Scope service) can apply to an assisted product, since each factory"
                        + " call creates a new instance.");
    }

    /** {@return a root over {@code factories}, binding a {@code String} that hosts shadow} */
    private static DefaultContext root(Factory<?>... factories) {
        return new DefaultContext(
                List.of(factories), List.of(new SingletonScope()), Map.of(Key.of(String.class), () -> "root"));
    }

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface WindowScoped {}

    @ScopingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface ViewScoped {}

    record Selection(String value) {}

    static final class Toolbar {}

    record Owner(Commands commands) {}

    record Commands(Provider<Owner> owner) {}

    /** {@code @ViewScoped Selection(String)}. */
    static class SelectionFactory extends Factory<Selection> {
        SelectionFactory() {
            super(Selection.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(ViewScoped.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Selection createInstance(Context context) {
            return new Selection(this.<String>getInstanceForConstructor(0, String.class, context));
        }
    }

    /** {@code @ViewScoped Selection(String)}, also exposed as {@code Record}. */
    static final class ExposedSelectionFactory extends SelectionFactory {
        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Selection.class), Key.of(Record.class));
        }
    }

    /** {@code @ViewScoped Selection(String)}, contributed to {@code Set<Record>}. */
    static final class ContributingSelectionFactory extends SelectionFactory {
        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(new Contribution(Key.of(Types.setOf(Record.class)), null));
        }
    }

    /** An assisted factory interface's factory declaring a {@code @Singleton} product. */
    static final class SingletonProductFactory extends Factory<Runnable> {
        SingletonProductFactory() {
            super(Runnable.class);
        }

        @Override
        protected List<ScopedProduct> scopedProducts() {
            return List.of(new ScopedProduct(Key.of(Selection.class), Singleton.class, "example.Factory.create"));
        }

        @Override
        protected Runnable createInstance(Context context) {
            return () -> {};
        }
    }

    /** {@code @WindowScoped Toolbar()}. */
    static final class ToolbarFactory extends Factory<Toolbar> {
        ToolbarFactory() {
            super(Toolbar.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(WindowScoped.class);
        }

        @Override
        protected Toolbar createInstance(Context context) {
            return new Toolbar();
        }
    }

    /** {@code @ViewScoped Owner(Commands)}. */
    static final class OwnerFactory extends Factory<Owner> {
        OwnerFactory() {
            super(Owner.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(ViewScoped.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("commands", Commands.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Owner createInstance(Context context) {
            return new Owner(this.<Commands>getInstanceForConstructor(0, Commands.class, context));
        }
    }

    /** {@code @ViewScoped Commands(Provider<Owner>)}. */
    static final class CommandsFactory extends Factory<Commands> {
        CommandsFactory() {
            super(Commands.class);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return Optional.of(ViewScoped.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("owner", Owner.class, true)), AnnotationMetadata.empty()));
        }

        @Override
        protected Commands createInstance(Context context) {
            return new Commands(this.<Owner>getProviderForConstructor(0, Owner.class, context));
        }
    }
}
