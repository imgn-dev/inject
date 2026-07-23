package be.imgn.inject.examples;

import be.imgn.inject.Inject;

/** Unscoped bean combining an application singleton with request-local data from the child injector. */
public final class RequestGreeter {
    private final Registry registry;
    private final RequestContext request;

    /**
     * @param registry the application-wide registry
     * @param request the request-local context
     */
    @Inject
    public RequestGreeter(Registry registry, RequestContext request) {
        this.registry = registry;
        this.request = request;
    }

    /** {@return the application-wide registry} */
    public Registry registry() {
        return registry;
    }

    /** {@return the request-local context} */
    public RequestContext request() {
        return request;
    }
}
