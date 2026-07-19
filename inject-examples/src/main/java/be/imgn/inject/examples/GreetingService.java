package be.imgn.inject.examples;

import java.time.Clock;
import java.util.logging.Logger;

import be.imgn.inject.Inject;
import be.imgn.inject.Named;
import be.imgn.inject.Provider;
import be.imgn.inject.log.Log;

/** Exercises constructor injection, a qualifier, a {@code Provider}, a singleton, and the {@code @Log} plugin. */
public final class GreetingService {

    private final String greeting;
    private final Provider<Clock> clock;
    private final Registry registry;

    @Log
    Logger logger;

    @Inject
    public GreetingService(@Named("greeting") String greeting, Provider<Clock> clock, Registry registry) {
        this.greeting = greeting;
        this.clock = clock;
        this.registry = registry;
    }

    /** {@return the injected greeting prefix} */
    public String greeting() {
        return greeting;
    }

    /** {@return a freshly resolved clock instant provider result} */
    public Clock now() {
        return clock.get();
    }

    /** {@return the injected singleton registry} */
    public Registry registry() {
        return registry;
    }

    /** {@return the plugin-injected logger} */
    public Logger logger() {
        return logger;
    }
}
