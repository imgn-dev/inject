package be.imgn.inject.context;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Key;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringJoiner;

/**
 * The per-thread resolution path of one context: detects dependency cycles and renders the requester chain for
 * missing-binding errors.
 */
final class ResolutionStack {
    private final ThreadLocal<Deque<Key<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Pushes {@code key}, first failing on a cycle.
     *
     * @param key the key being resolved
     * @throws CircularDependencyException if {@code key} is already being resolved on this thread
     */
    void enter(Key<?> key) {
        Deque<Key<?>> current = stack.get();
        if (current.contains(key)) {
            StringJoiner path = new StringJoiner(" -> ");
            current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
            path.add(key.toString());
            throw new CircularDependencyException("Dependency cycle: " + path);
        }
        current.push(key);
    }

    /** Pops the innermost key. */
    void exit() {
        stack.get().pop();
    }

    /** {@return the requester-chain suffix for error messages; empty when nothing is resolving} */
    String requiredBySuffix() {
        Deque<Key<?>> current = stack.get();
        if (current.isEmpty()) {
            return "";
        }
        StringJoiner path = new StringJoiner(" -> ");
        current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
        return " (required by " + path + ")";
    }
}
