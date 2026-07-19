package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;

/** A singleton-scoped bean. */
@Singleton
public final class Widget {

    @Inject
    public Widget() {}
}
