package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Injector;

/** Demonstrates injector injection: resolves other beans through the {@link Injector} that built it. */
public final class BeanLocator {
    private final Injector injector;

    /** @param injector the injector that built this bean */
    @Inject
    public BeanLocator(Injector injector) {
        this.injector = injector;
    }

    /** {@return the injector this bean was built by} */
    public Injector injector() {
        return injector;
    }

    /** {@return the registry resolved on demand through the injector} */
    public Registry locateRegistry() {
        return injector.getInstance(Registry.class);
    }
}
