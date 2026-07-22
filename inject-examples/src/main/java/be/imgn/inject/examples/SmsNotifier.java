package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Always-on notifier contributed to {@code Set<Notifier>}. */
@IntoSet(Notifier.class)
public final class SmsNotifier implements Notifier {
    @Inject
    public SmsNotifier() {}
}
