package it;

import be.imgn.inject.Inject;

/** An injectable bean, so the processor generates a factory. */
public class Widget {
    @Inject
    public Widget() {}
}
