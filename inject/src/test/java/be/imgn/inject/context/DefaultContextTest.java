package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Key;
import be.imgn.inject.UnsatisfiedDependencyException;
import be.imgn.inject.context.fixtures.CycleAFactory;
import be.imgn.inject.context.fixtures.CycleBFactory;
import be.imgn.inject.context.fixtures.LeafFactory;
import be.imgn.inject.context.fixtures.RootFactory;

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
                .withMessageContaining("->");
    }

    @Test
    void createWiresUpServiceLoaderContextWithoutThrowing() {
        Context context = Context.create();
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get())
                .withMessageContaining("Long");
    }
}
