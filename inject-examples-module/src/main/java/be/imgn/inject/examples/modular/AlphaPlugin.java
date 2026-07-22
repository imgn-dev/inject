package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** First contributed plugin. */
@IntoSet(Plugin.class)
public final class AlphaPlugin implements Plugin {
    @Inject
    public AlphaPlugin() {}
}
