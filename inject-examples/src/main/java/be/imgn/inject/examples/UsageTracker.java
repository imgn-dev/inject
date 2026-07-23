package be.imgn.inject.examples;

/** Reports how bean usage is tracked; the container falls back to a no-op tracker when metering is off. */
public interface UsageTracker {

    /** {@return a short description of the tracking mode} */
    String describe();
}
