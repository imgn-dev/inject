package be.imgn.inject.examples;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Contributed to {@code Set<Notifier>} only when {@code examples.push} is {@code true}. */
@IntoSet(Notifier.class)
@ConditionalOnProperty(value = "examples.push", havingValue = "true")
public final class PushNotifier implements Notifier {
    @Inject
    public PushNotifier() {}
}
