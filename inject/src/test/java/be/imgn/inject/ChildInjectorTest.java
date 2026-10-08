package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChildInjectorTest {

    @Test
    void childSeesOwnAndParentBindings() {
        Injector parent = Injector.builder().bind(String.class, "parent").build();
        Injector child = parent.childBuilder().bind(Integer.class, 7).build();
        assertThat(child.getInstance(Integer.class)).isEqualTo(7);
        assertThat(child.getInstance(String.class)).isEqualTo("parent");
    }

    @Test
    void childShadowsParentWithoutAffectingIt() {
        Injector parent = Injector.builder().bind(String.class, "parent").build();
        Injector child = parent.childBuilder().bind(String.class, "child").build();
        assertThat(child.getInstance(String.class)).isEqualTo("child");
        assertThat(parent.getInstance(String.class)).isEqualTo("parent");
    }

    @Test
    void bindProviderIsFreshPerResolution() {
        AtomicInteger counter = new AtomicInteger();
        Injector child = Injector.create()
                .childBuilder()
                .bindProvider(Integer.class, counter::incrementAndGet)
                .build();
        assertThat(child.getInstance(Integer.class)).isEqualTo(1);
        assertThat(child.getInstance(Integer.class)).isEqualTo(2);
    }

    @Test
    void nestedChildrenResolveNearestWins() {
        Injector parent = Injector.builder().bind(String.class, "root").build();
        Injector child = parent.childBuilder().bind(String.class, "mid").build();
        Injector grandchild = child.childBuilder().bind(Integer.class, 1).build();
        assertThat(grandchild.getInstance(String.class)).isEqualTo("mid");
        assertThat(grandchild.getInstance(Integer.class)).isEqualTo(1);
    }

    @Test
    void injectorThroughChildIsTheChild() {
        Injector parent = Injector.create();
        Injector child = parent.childBuilder().build();
        assertThat(child.getInstance(Injector.class)).isSameAs(child);
        assertThat(parent.getInstance(Injector.class)).isSameAs(parent);
    }

    @Test
    void doubleBindInOneChildBuilderThrows() {
        Injector.ChildBuilder builder = Injector.create().childBuilder().bind(String.class, "a");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(String.class, "b"))
                .withMessageContaining("already bound");
    }

    @Test
    // The raw key smuggles a String past the compile-time type check.
    @SuppressWarnings({"rawtypes", "unchecked"})
    void childBuilderRejectsFrameworkTypes() {
        Injector.ChildBuilder builder = Injector.create().childBuilder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(Injector.class, Injector.create()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bindProvider(Context.class, () -> {
                    throw new UnsupportedOperationException();
                }));
        Key factoryKey = Key.of(Factory.class);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(factoryKey, "notAFactory"));
    }

    @Test
    void rootBuilderBindProviderWorks() {
        AtomicInteger counter = new AtomicInteger();
        Injector injector = Injector.builder()
                .bindProvider(Integer.class, counter::incrementAndGet)
                .build();
        assertThat(injector.getInstance(Integer.class)).isEqualTo(1);
        assertThat(injector.getInstance(Integer.class)).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("NullAway")
    void childBuilderRejectsNulls() {
        Injector.ChildBuilder builder = Injector.create().childBuilder();
        assertThatNullPointerException().isThrownBy(() -> builder.bind(String.class, null));
        assertThatNullPointerException().isThrownBy(() -> builder.bindProvider(String.class, null));
    }
}
