package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.Singleton;

class InterceptorIndexTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Timed {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Audited {}

    /** A no-op interceptor that reports which bean instance served the call. */
    record Noop(String name) implements MethodInterceptor {
        @Override
        public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    /** Factory for an interceptor bean; scoped when {@code singleton}, prototype otherwise. */
    static final class NoopFactory extends Factory<Noop> {
        private final Class<? extends Annotation> handled;
        private final String name;
        private final boolean singleton;

        NoopFactory(Class<? extends Annotation> handled, String name, boolean singleton) {
            super(Noop.class);
            this.handled = handled;
            this.name = name;
            this.singleton = singleton;
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
            return List.of(Key.of(Noop.class, Names.named(name)));
        }
    }

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
}
