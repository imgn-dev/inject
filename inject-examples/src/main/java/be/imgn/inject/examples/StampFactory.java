package be.imgn.inject.examples;

import be.imgn.inject.AssistedFactory;

/** Creates {@link LegacyStamp}s through the assisted {@code @Provides} recipe. */
public interface StampFactory {
    /**
     * {@return a stamp with the caller-supplied suffix}
     *
     * @param suffix the suffix
     */
    @AssistedFactory(value = StampConfig.class, method = "stamp")
    LegacyStamp create(String suffix);
}
