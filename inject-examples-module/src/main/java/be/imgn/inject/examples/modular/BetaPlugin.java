package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Second contributed plugin. */
@IntoSet(Plugin.class)
public final class BetaPlugin implements Plugin {
    @Inject
    public BetaPlugin() {}
}
