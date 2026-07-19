package be.imgn.inject.examples;

import java.util.logging.Logger;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;
import be.imgn.inject.log.Log;

/** A singleton-scoped collaborator whose logger is injected through the constructor by the {@code @Log} plugin. */
@Singleton
public final class Registry {

    private final Logger logger;

    @Inject
    public Registry(@Log Logger logger) {
        this.logger = logger;
    }

    /** {@return the plugin-injected logger} */
    public Logger logger() {
        return logger;
    }
}
