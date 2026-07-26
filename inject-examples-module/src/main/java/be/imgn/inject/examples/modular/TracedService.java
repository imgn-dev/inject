package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;

/** Module-path bean whose call is intercepted. */
public class TracedService {

    /** Creates the service. */
    @Inject
    public TracedService() {}

    /** {@return the service's own answer, before interception} */
    @Traced
    public String answer() {
        return "answer";
    }
}
