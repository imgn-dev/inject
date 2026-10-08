package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.AssistedFactory;

/** Creates {@link Badge}s from caller-supplied labels. */
public interface BadgeFactory {
    /**
     * Returns a badge for {@code owner} with {@code role}.
     *
     * @param owner the owner label
     * @param role the role label
     * @return a badge for {@code owner} with {@code role}
     */
    @AssistedFactory
    Badge create(String owner, @Assisted("role") String role);
}
