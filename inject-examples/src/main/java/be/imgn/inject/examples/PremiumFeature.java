package be.imgn.inject.examples;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;

/**
 * A bean present only when the {@code examples.premium} property is {@code true}. Demonstrates conditional
 * registration: without the property the bean is absent from the graph entirely.
 */
@ConditionalOnProperty(value = "examples.premium", havingValue = "true")
public final class PremiumFeature {
    @Inject
    public PremiumFeature() {}
}
