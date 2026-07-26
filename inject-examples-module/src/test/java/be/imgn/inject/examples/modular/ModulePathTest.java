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

    @Test
    void collectsSetContributionsOnTheModulePath() {
        PluginHost host = Injector.create().getInstance(PluginHost.class);
        assertThat(host.plugins())
                .hasSize(2)
                .hasAtLeastOneElementOfType(AlphaPlugin.class)
                .hasAtLeastOneElementOfType(BetaPlugin.class);
    }

    @Test
    void assistedFactoryWorksOnTheModulePath() {
        Injector injector = Injector.create();
        Ticket ticket = injector.getInstance(TicketFactory.class).create("vip");
        assertThat(ticket).returns("vip", Ticket::label).returns(injector.getInstance(Widget.class), Ticket::widget);
    }

    @Test
    void defaultTelemetryFallsBackWhenLiveTelemetryIsOff() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Telemetry.class).mode()).isEqualTo("off");
    }

    @Test
    void liveTelemetryWinsOverTheDefaultWhenEnabled() {
        System.setProperty("modular.telemetry", "on");
        try {
            Injector injector = Injector.create();
            assertThat(injector.getInstance(Telemetry.class).mode()).isEqualTo("live");
        } finally {
            System.clearProperty("modular.telemetry");
        }
    }

    @Test
    void interceptsOnTheModulePath() {
        try (Injector injector = Injector.create()) {
            assertThat(injector.getInstance(TracedService.class).answer()).isEqualTo("traced:answer");
        }
    }
}
