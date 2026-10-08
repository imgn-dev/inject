package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;
import be.imgn.inject.log.Log;
import java.util.logging.Logger;

/** A singleton-scoped collaborator whose logger is injected through the constructor by the {@code @Log} plugin. */
@Singleton
public final class Registry implements Cataloged {

    private final Logger logger;

    @Inject
    public Registry(@Log Logger logger) {
        this.logger = logger;
    }

    /**
     * Returns the plugin-injected logger.
     *
     * @return the plugin-injected logger
     */
    public Logger logger() {
        return logger;
    }
}
