package be.imgn.inject.examples.modular;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;

class ModulePathTest {

    @Test
    void resolvesFactoriesDeclaredViaProvidesOnTheModulePath() {
        Injector injector = Injector.create();

        Service service = injector.getInstance(Service.class);
        assertThat(service).isNotNull();
        assertThat(service.widget()).isNotNull();
    }

    @Test
    void singletonHoldsAcrossResolutions() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Widget.class)).isSameAs(injector.getInstance(Widget.class));
    }
}
