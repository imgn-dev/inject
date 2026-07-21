package it;

import be.imgn.inject.Inject;

/** An injectable bean, so the processor generates a factory to register. */
public class Service {
    @Inject
    public Service() {}
}
