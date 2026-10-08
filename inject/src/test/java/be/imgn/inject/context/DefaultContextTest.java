package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;
import be.imgn.inject.context.fixtures.CycleAFactory;
import be.imgn.inject.context.fixtures.CycleBFactory;
import be.imgn.inject.context.fixtures.LeafFactory;
import be.imgn.inject.context.fixtures.RootFactory;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultContextTest {
    @Test
    void resolvesPrototypeFresh() {
        Context context = new DefaultContext(List.of(new LeafFactory()), List.of());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("leaf");
    }

    @Test
    void singletonScopedBeanReturnsSameInstance() {
        Context context =
                new DefaultContext(List.of(new LeafFactory(), new RootFactory()), List.of(new SingletonScope()));
        var provider = context.getProvider(Key.of(RootFactory.Root.class));
        assertThat(provider.get()).isSameAs(provider.get());
    }

    @Test
    void missingBindingThrowsUnsatisfied() {
        Context context = new DefaultContext(List.of(new LeafFactory()), List.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get())
                .withMessageContaining("Long");
    }

    @Test
    void cycleThrowsWithPath() {
        Context context = new DefaultContext(List.of(new CycleAFactory(), new CycleBFactory()), List.of());
        assertThatExceptionOfType(CircularDependencyException.class)
                .isThrownBy(
                        () -> context.getProvider(Key.of(CycleAFactory.A.class)).get())
                .withMessage("Dependency cycle: "
                        + Key.of(CycleAFactory.A.class)
                        + " -> "
                        + Key.of(CycleBFactory.B.class)
                        + " -> "
                        + Key.of(CycleAFactory.A.class));
    }

    @Test
    void missingTransitiveDependencyReportsRequesterChain() {
        Context context = new DefaultContext(List.of(new RootFactory()), List.of(new SingletonScope()));
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() ->
                        context.getProvider(Key.of(RootFactory.Root.class)).get())
                .withMessageContaining("No binding for " + Key.of(String.class))
                .withMessageContaining("required by " + Key.of(RootFactory.Root.class));
    }

    @Test
    @SuppressWarnings("NullAway")
    void getProviderRejectsNullKey() {
        Context context = new DefaultContext(List.of(new LeafFactory()), List.of());
        assertThatNullPointerException().isThrownBy(() -> context.getProvider(null));
    }

    @Test
    void noArgConstructorWiresUpServiceLoaderContextWithoutThrowing() {
        Context context = new DefaultContext();
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get())
                .withMessageContaining("Long");
    }

    @Test
    void twoScopesForOneAnnotationAreAnError() {
        List<Scope> scopes = List.of(new SingletonScope(), new ReplacementSingletonScope());
        assertThatExceptionOfType(ScopeResolutionException.class)
                .isThrownBy(() -> new DefaultContext(List.of(new LeafFactory()), scopes))
                .withMessageContaining("Duplicate scope for @Singleton")
                .withMessageContaining(SingletonScope.class.getName())
                .withMessageContaining(ReplacementSingletonScope.class.getName());
    }

    /** A second scope claiming {@code @Singleton}, as a stray jar on the classpath could. */
    static final class ReplacementSingletonScope implements Scope {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Singleton.class;
        }

        @Override
        public <T> Provider<T> wrap(Provider<T> provider) {
            return provider;
        }
    }
}
