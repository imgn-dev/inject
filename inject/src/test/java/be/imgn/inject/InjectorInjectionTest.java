package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.inject.context.Context;
import be.imgn.inject.context.DefaultContext;
import be.imgn.inject.context.Factory;
import org.junit.jupiter.api.Test;

class InjectorInjectionTest {

    @Test
    void injectedInjectorIsTheCreatedInstance() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Injector.class)).isSameAs(injector);
        assertThat(injector.getProvider(Injector.class).get()).isSameAs(injector);
    }

    @Test
    void builderBuiltInjectorAlsoSelfBinds() {
        Injector injector = Injector.builder().bind(String.class, "x").build();
        assertThat(injector.getInstance(Injector.class)).isSameAs(injector);
    }

    @Test
    void injectorBindingIsNotABeanDefinition() {
        assertThat(Injector.create().getBeanDefinition(Key.of(Injector.class))).isEmpty();
    }

    @Test
    void bareContextWithoutInjectorRefusesResolution() {
        Context context = new DefaultContext();
        assertThatIllegalStateException()
                .isThrownBy(() -> context.getProvider(Key.of(Injector.class)).get())
                .withMessageContaining("no Injector attached");
    }

    @Test
    void setInjectorIsOnceOnly() {
        DefaultContext context = new DefaultContext();
        Injector injector = Injector.create();
        context.setInjector(injector);
        assertThatIllegalStateException()
                .isThrownBy(() -> context.setInjector(injector))
                .withMessageContaining("already");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void builderRejectsFrameworkTypes() {
        Injector.Builder builder = Injector.builder();
        Injector injector = Injector.create();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(Injector.class, injector));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(Key.of(Context.class), new DefaultContext()));
        Key factoryKey = Key.of(Factory.class);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(factoryKey, "notAFactory"));
    }
}
