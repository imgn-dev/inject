package be.imgn.inject.examples.modular;

import be.imgn.inject.AssistedFactory;

/** Creates {@link Ticket}s. */
public interface TicketFactory {
    /**
     * Returns a ticket with {@code label}.
     *
     * @param label the label
     * @return a ticket with {@code label}
     */
    @AssistedFactory
    Ticket create(String label);
}
