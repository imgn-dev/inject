package be.imgn.inject.examples;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;

/** The real tracker, present only when {@code examples.metering=true}; it displaces the default tracker. */
@ConditionalOnProperty(value = "examples.metering", havingValue = "true")
public final class MeteredTracker implements UsageTracker {

    @Inject
    MeteredTracker() {}

    @Override
    public String describe() {
        return "metered";
    }
}
