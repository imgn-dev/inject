package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class InterceptorIndexTest {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Timed {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Audited {}

    @Test
    void indexesInterceptorBeansByAnnotationType() {
        DefaultContext context = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true), new NoopFactory(Audited.class, "audit", true)),
                List.of(new SingletonScope()));

        assertThat(context.interceptor(Timed.class).get()).isEqualTo(new Noop("timing"));
        assertThat(context.interceptor(Audited.class).get()).isEqualTo(new Noop("audit"));
    }

    @Test
    void interceptorProviderRespectsTheInterceptorsOwnScope() {
        DefaultContext singletons = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true)), List.of(new SingletonScope()));
        DefaultContext prototypes = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", false)), List.of(new SingletonScope()));

        assertThat(singletons.interceptor(Timed.class).get())
                .isSameAs(singletons.interceptor(Timed.class).get());
        assertThat(prototypes.interceptor(Timed.class).get())
                .isNotSameAs(prototypes.interceptor(Timed.class).get());
    }

    @Test
    void twoInterceptorsForOneAnnotationAreADuplicateBinding() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(
                        List.of(
                                new NoopFactory(Timed.class, "first", true),
                                new NoopFactory(Timed.class, "second", true)),
                        List.of(new SingletonScope())))
                .withMessageContaining("Timed");
    }

    @Test
    void anUnhandledAnnotationIsRejectedWithAnActionableMessage() {
        DefaultContext context = new DefaultContext(List.of(), List.of());

        assertThatExceptionOfType(InterceptorResolutionException.class)
                .isThrownBy(() -> context.interceptor(Timed.class))
                .withMessageContaining("Timed")
                .withMessageContaining("@Intercepts");
    }

    @Test
    void aChildDelegatesInterceptorLookupToItsParent() {
        DefaultContext parent = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true)), List.of(new SingletonScope()));
        ChildContext child = new ChildContext(parent, Map.of());

        assertThat(child.interceptor(Timed.class).get()).isEqualTo(new Noop("timing"));
    }

    @Test
    void aClosedChildRejectsInterceptorLookup() {
        DefaultContext parent = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true)), List.of(new SingletonScope()));
        ChildContext child = new ChildContext(parent, Map.of());
        child.close();

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> child.interceptor(Timed.class));
    }

    @Test
    void anOverrideForAnInterceptorBeansOwnKeyReachesTheChainToo() {
        Noop stub = new Noop("stub");
        DefaultContext context = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true, false)),
                List.of(new SingletonScope()),
                Map.of(Key.of(Noop.class), (Provider<Noop>) () -> stub));

        assertThat(context.getProvider(Key.of(Noop.class)).get())
                .as("the override replaces the interceptor bean for direct resolution")
                .isEqualTo(stub);
        assertThat(context.interceptor(Timed.class).get())
                .as("the override replaces the interceptor bean in the chain too")
                .isEqualTo(stub);
    }

    @Test
    void aChildOverrideForAnInterceptorBeansOwnKeyDoesNotReachTheChain() {
        DefaultContext parent = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true, false)), List.of(new SingletonScope()));
        Noop stub = new Noop("stub");
        ChildContext child = new ChildContext(parent, Map.of(Key.of(Noop.class), (Provider<Noop>) () -> stub));

        assertThat(child.getProvider(Key.of(Noop.class)).get())
                .as("the child's binding shadows direct resolution through the child")
                .isEqualTo(stub);
        assertThat(child.interceptor(Timed.class).get())
                .as("but ChildContext.interceptor delegates to the parent, so the chain still runs the original")
                .isEqualTo(new Noop("timing"));
    }

    /** A no-op interceptor that reports which bean instance served the call. */
    record Noop(String name) implements MethodInterceptor {
        @Override
        public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    /**
     * Factory for an interceptor bean; scoped when {@code singleton}, prototype otherwise. Exposes a qualified key
     * (distinct across instances) unless {@code qualified} is {@code false}, in which case it exposes the bean's bare
     * unqualified key so a test can override it the way {@code Injector.builder()} overrides an interceptor bean.
     */
    static final class NoopFactory extends Factory<Noop> {
        private final Class<? extends Annotation> handled;
        private final String name;
        private final boolean singleton;
        private final boolean qualified;

        NoopFactory(Class<? extends Annotation> handled, String name, boolean singleton) {
            this(handled, name, singleton, true);
        }

        NoopFactory(Class<? extends Annotation> handled, String name, boolean singleton, boolean qualified) {
            super(Noop.class);
            this.handled = handled;
            this.name = name;
            this.singleton = singleton;
            this.qualified = qualified;
        }

        @Override
        protected Noop createInstance(Context context) {
            return new Noop(name);
        }

        @Override
        protected Optional<Class<? extends Annotation>> intercepts() {
            return Optional.of(handled);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return singleton ? Optional.of(Singleton.class) : Optional.empty();
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return qualified ? List.of(Key.of(Noop.class, Names.named(name))) : List.of(Key.of(Noop.class));
        }
    }
}
