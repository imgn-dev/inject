package be.imgn.inject.examples;

import be.imgn.inject.Default;
import be.imgn.inject.Provides;

/** Hosts the fallback {@link UsageTracker} recipe, used only when no live tracker binding exists. */
public final class TrackerConfig {

    private TrackerConfig() {}

    /** {@return the no-op tracker bound when metering is disabled} */
    @Provides
    @Default
    static UsageTracker noopTracker() {
        return () -> "noop";
    }
}
