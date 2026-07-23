package be.imgn.inject.examples.modular;

import be.imgn.inject.AssistedFactory;

/** Creates {@link Ticket}s. */
public interface TicketFactory {
    /**
     * {@return a ticket with {@code label}}
     *
     * @param label the label
     */
    @AssistedFactory
    Ticket create(String label);
}
