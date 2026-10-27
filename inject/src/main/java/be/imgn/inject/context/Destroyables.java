package be.imgn.inject.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The instances a context destroys when it closes, by their factory's {@code @PreDestroy}, newest first. */
final class Destroyables {
    private final Deque<Runnable> callbacks = new ArrayDeque<>();

    /** Records {@code instance}, to destroy with {@code factory} when the context closes. */
    <T> void track(Factory<T> factory, T instance) {
        synchronized (callbacks) {
            callbacks.push(() -> factory.preDestroy(instance));
        }
    }

    /** {@return the destroy callbacks of every tracked instance, newest first, and forgets them} */
    List<Runnable> drain() {
        synchronized (callbacks) {
            List<Runnable> drained = new ArrayList<>(callbacks);
            callbacks.clear();
            return drained;
        }
    }

    /**
     * Runs every action, in order, even after one fails. The first failure is thrown, with the later ones added to it
     * as suppressed exceptions.
     */
    // One failing @PreDestroy, or one child failing to close, must not keep the remaining actions from running.
    @SuppressWarnings("checkstyle:IllegalCatch")
    static void runAll(List<Runnable> actions) {
        @Nullable RuntimeException failure = null;
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
