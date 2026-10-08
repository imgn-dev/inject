package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.PostConstruct;
import be.imgn.inject.PreDestroy;
import be.imgn.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/** A singleton recording its own lifecycle, to demonstrate @PostConstruct and @PreDestroy. */
@Singleton
public final class Ledger {
    private final List<String> events = new ArrayList<>();

    /** Creates an empty ledger. */
    @Inject
    public Ledger() {}

    /**
     * Returns the recorded lifecycle events, in order.
     *
     * @return the recorded lifecycle events, in order
     */
    public List<String> events() {
        return events;
    }

    @PostConstruct
    void opened() {
        events.add("opened");
    }

    @PreDestroy
    void closed() {
        events.add("closed");
    }
}
