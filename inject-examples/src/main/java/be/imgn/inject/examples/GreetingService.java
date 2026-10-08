package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Named;
import be.imgn.inject.Provider;
import be.imgn.inject.log.Log;
import java.time.Clock;
import java.util.logging.Logger;

/** Exercises constructor injection, a qualifier, a {@code Provider}, a singleton, and the {@code @Log} plugin. */
public final class GreetingService {

    // Package-private: the generated factory, in this package, assigns plugin-injected fields directly.
    @Log
    @SuppressWarnings("checkstyle:VisibilityModifier")
    Logger logger;

    private final String greeting;
    private final Provider<Clock> clock;
    private final Registry registry;

    @Inject
    public GreetingService(@Named("greeting") String greeting, Provider<Clock> clock, Registry registry) {
        this.greeting = greeting;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * Returns the injected greeting prefix.
     *
     * @return the injected greeting prefix
     */
    public String greeting() {
        return greeting;
    }

    /**
     * Returns a freshly resolved clock instant provider result.
     *
     * @return a freshly resolved clock instant provider result
     */
    public Clock now() {
        return clock.get();
    }

    /**
     * Returns the injected singleton registry.
     *
     * @return the injected singleton registry
     */
    public Registry registry() {
        return registry;
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
