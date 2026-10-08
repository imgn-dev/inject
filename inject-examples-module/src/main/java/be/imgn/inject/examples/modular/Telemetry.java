package be.imgn.inject.examples.modular;

/** Reports the telemetry mode; a {@code @Default} recipe supplies the off mode when live telemetry is disabled. */
public interface Telemetry {

    /**
     * Returns the active telemetry mode.
     *
     * @return the active telemetry mode
     */
    String mode();
}
