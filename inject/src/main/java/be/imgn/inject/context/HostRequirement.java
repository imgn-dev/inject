package be.imgn.inject.context;

import be.imgn.inject.Key;

/**
 * A dependency of a child-local bean that the root does not bind, so the children hosting its scope must.
 *
 * @param key the dependency's key
 * @param description the line reporting it unbound, naming the bean and its injection point
 */
record HostRequirement(Key<?> key, String description) {}
