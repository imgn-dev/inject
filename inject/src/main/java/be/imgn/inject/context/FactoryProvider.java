package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.util.Objects;

/**
 * A {@link Provider} that builds instances by invoking a {@link Factory} with a {@link Context}.
 *
 * @param <T> the provided type
 */
public final class FactoryProvider<T> implements Provider<T> {
    private final Context context;
    private final Factory<T> factory;

    /**
     * Creates a provider backed by {@code factory}.
     *
     * @param context the resolution context
     * @param factory the backing factory
     */
    public FactoryProvider(Context context, Factory<T> factory) {
        this.context = Objects.requireNonNull(context, "context");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public T get() {
        return factory.build(context);
    }
}
