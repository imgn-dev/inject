package be.imgn.inject.examples;

/** Request-local data; the application default is shadowed by each request's child injector. */
public record RequestContext(String user) {
    /** The application-level default: no user. */
    public RequestContext() {
        this("anonymous");
    }
}
