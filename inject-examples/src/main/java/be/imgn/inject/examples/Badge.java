package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.Inject;

/** An assisted bean: the registry comes from the container, the labels from the factory caller. */
public final class Badge {
    private final Registry registry;
    private final String owner;
    private final String role;

    /**
     * @param registry the container-resolved registry
     * @param owner the caller-supplied owner label
     * @param role the caller-supplied role label
     */
    @Inject
    public Badge(Registry registry, @Assisted String owner, @Assisted("role") String role) {
        this.registry = registry;
        this.owner = owner;
        this.role = role;
    }

    /**
     * Returns the container-resolved registry.
     *
     * @return the container-resolved registry
     */
    public Registry registry() {
        return registry;
    }

    /**
     * Returns the caller-supplied owner label.
     *
     * @return the caller-supplied owner label
     */
    public String owner() {
        return owner;
    }

    /**
     * Returns the caller-supplied role label.
     *
     * @return the caller-supplied role label
     */
    public String role() {
        return role;
    }
}
