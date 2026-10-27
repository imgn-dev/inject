package be.imgn.inject;

/**
 * Observes the instances the container builds. Contribute one with {@code @IntoSet(ConstructionListener.class)}; the
 * root injector resolves every listener when it is created.
 *
 * <p>The generated factories call each listener synchronously, on the constructing thread, once an instance is fully
 * built: after its constructor, member injection and {@code @PostConstruct}. That includes {@code @Provides} results,
 * interception proxies and assisted products. Instances bound with {@code bind(...)} are not reported, nor are those
 * built while the listeners themselves are being resolved: the listeners and their dependencies. An exception thrown by
 * a listener fails that construction: the instance is neither cached nor destroyed.
 */
@FunctionalInterface
public interface ConstructionListener {
    /**
     * Called with each instance the container builds.
     *
     * @param instance the fully built instance
     * @param injector the injector the instance belongs to: the one that built it, or for a bean of a child-local
     *     scope, the child that hosts it
     */
    void constructed(Object instance, Injector injector);
}
