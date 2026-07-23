package be.imgn.inject.examples.modular;

import be.imgn.inject.Default;
import be.imgn.inject.Provides;

/** Hosts the fallback {@link Telemetry} recipe for the module-path example. */
public final class TelemetryConfig {

    private TelemetryConfig() {}

    /** {@return the disabled-mode telemetry bound when no live telemetry exists} */
    @Provides
    @Default
    static Telemetry offTelemetry() {
        return () -> "off";
    }
}
