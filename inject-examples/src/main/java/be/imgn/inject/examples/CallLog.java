package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/** Shared sink both interceptors write to, showing that an interceptor is an injected bean like any other. */
@Singleton
public class CallLog {

    private final List<String> entries = new ArrayList<>();

    /** Creates an empty log. */
    @Inject
    public CallLog() {}

    /**
     * Appends one entry.
     *
     * @param entry the entry to record
     */
    public void add(String entry) {
        entries.add(entry);
    }

    /**
     * Returns the entries recorded so far, in order.
     *
     * @return the entries recorded so far, in order
     */
    public List<String> entries() {
        return List.copyOf(entries);
    }
}
