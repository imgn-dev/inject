package be.imgn.inject.processor;

import org.jspecify.annotations.Nullable;

/**
 * The lifecycle callback method names declared on a bean.
 *
 * @param postConstruct the {@code @PostConstruct} method name, or {@code null}
 * @param preDestroy the {@code @PreDestroy} method name, or {@code null}
 */
record LifecycleCallbacks(
        @Nullable String postConstruct, @Nullable String preDestroy) {}
