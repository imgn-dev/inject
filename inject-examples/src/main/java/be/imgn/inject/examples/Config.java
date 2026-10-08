package be.imgn.inject.examples;

import be.imgn.inject.Named;
import be.imgn.inject.Provides;
import java.time.Clock;

/** Supplies types the container does not construct itself. */
public final class Config {

    private Config() {}

    /** {@return the greeting prefix} */
    @Provides
    @Named("greeting")
    static String greeting() {
        return "Hello";
    }

    /** {@return the system clock} */
    @Provides
    static Clock clock() {
        return Clock.systemUTC();
    }
}
