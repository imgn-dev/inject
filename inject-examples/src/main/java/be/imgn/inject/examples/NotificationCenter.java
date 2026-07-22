package be.imgn.inject.examples;

import java.util.Map;
import java.util.Set;

import be.imgn.inject.Inject;

/** Consumes both collection bindings: every notifier, and formatters by name. */
public final class NotificationCenter {
    private final Set<Notifier> notifiers;
    private final Map<String, Formatter> formatters;

    /**
     * @param notifiers every contributed notifier
     * @param formatters formatters by format name
     */
    @Inject
    public NotificationCenter(Set<Notifier> notifiers, Map<String, Formatter> formatters) {
        this.notifiers = notifiers;
        this.formatters = formatters;
    }

    /** {@return every contributed notifier} */
    public Set<Notifier> notifiers() {
        return notifiers;
    }

    /** {@return formatters by format name} */
    public Map<String, Formatter> formatters() {
        return formatters;
    }
}
