package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;

/** A singleton-scoped collaborator. */
@Singleton
public final class Registry {

    @Inject
    public Registry() {}
}
