package be.imgn.inject.examples;

import be.imgn.inject.Inject;

/**
 * A {@link ThreadScoped} bean: one token instance per thread, so its identity marks a per-thread (request-like) scope.
 */
@ThreadScoped
public final class RequestScopedToken {
    @Inject
    public RequestScopedToken() {}
}
