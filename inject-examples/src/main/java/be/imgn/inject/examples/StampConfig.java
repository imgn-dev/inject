package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.Named;
import be.imgn.inject.Provides;

/** Hosts the assisted {@code @Provides} recipe for {@link LegacyStamp}. */
public class StampConfig {
    /**
     * {@return a stamp combining the container-resolved greeting with the caller's suffix}
     *
     * @param greeting the container-resolved greeting
     * @param suffix the caller-supplied suffix
     */
    @Provides
    public static LegacyStamp stamp(@Named("greeting") String greeting, @Assisted String suffix) {
        return new LegacyStamp(greeting + " " + suffix);
    }
}
