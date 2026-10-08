package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import java.util.Map;
import java.util.Set;

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

    /**
     * Returns every contributed notifier.
     *
     * @return every contributed notifier
     */
    public Set<Notifier> notifiers() {
        return notifiers;
    }

    /**
     * Returns formatters by format name.
     *
     * @return formatters by format name
     */
    public Map<String, Formatter> formatters() {
        return formatters;
    }
}
