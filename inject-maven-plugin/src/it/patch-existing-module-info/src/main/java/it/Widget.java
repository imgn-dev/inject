package it;

import be.imgn.inject.Inject;

/** A trivial injectable bean, so the processor generates a factory the plugin must register. */
public class Widget {
    @Inject
    public Widget() {}
}
