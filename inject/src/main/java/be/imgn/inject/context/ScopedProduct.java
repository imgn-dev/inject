package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import be.imgn.inject.Key;
import java.lang.annotation.Annotation;

/**
 * The product of an assisted factory method whose recipe has a child-local scope. Each call creates the instance in the
 * nearest injector of the chain that hosts {@code scope}, which caches it: from then on, it resolves by {@code key}
 * through that injector and its descendants.
 *
 * @param key the key the product resolves by once created
 * @param scope the product's child-local scope
 * @param creator the factory method that creates it, as error messages name it
 */
public record ScopedProduct(Key<?> key, Class<? extends Annotation> scope, String creator) {

    /**
     * Creates a scoped product.
     *
     * @param key the key the product resolves by once created
     * @param scope the product's child-local scope
     * @param creator the factory method that creates it, as error messages name it
     */
    public ScopedProduct {
        requireNonNull(key, "key");
        requireNonNull(scope, "scope");
        requireNonNull(creator, "creator");
    }
}
