package be.imgn.inject.context;

import java.lang.annotation.Annotation;

/**
 * A direct dependency of a child-local bean on a bean of another child-local scope, which the children hosting the
 * first scope need a host of above them.
 *
 * @param scope the scope of the dependency
 * @param description the line reporting no host of {@code scope} above, naming the bean and its injection point
 */
record HostScopeRequirement(Class<? extends Annotation> scope, String description) {}
