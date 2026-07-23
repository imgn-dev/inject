package be.imgn.inject.examples.modular;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;

/** The real telemetry bean, present only when {@code modular.telemetry=on}. */
@ConditionalOnProperty(value = "modular.telemetry", havingValue = "on")
public final class LiveTelemetry implements Telemetry {

    @Inject
    LiveTelemetry() {}

    @Override
    public String mode() {
        return "live";
    }
}
