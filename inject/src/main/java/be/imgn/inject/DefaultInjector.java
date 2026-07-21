package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import be.imgn.inject.context.BeanDefinition;
import be.imgn.inject.context.Context;

/** Default {@link Injector} delegating everything to a {@link Context}. */
final class DefaultInjector implements Injector {
    private final Context context;

    DefaultInjector(Context context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        return getProvider(type).get();
    }

    @Override
    public <T> T getInstance(Key<T> key) {
        return getProvider(key).get();
    }

    @Override
    public <T> T getInstance(TypeLiteral<T> type) {
        return getProvider(type).get();
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return getProvider(Key.of(Objects.requireNonNull(type, "type")));
    }

    @Override
    public <T> Provider<T> getProvider(TypeLiteral<T> type) {
        return getProvider(Key.of(Objects.requireNonNull(type, "type")));
    }

    @Override
    public <T> Provider<T> getProvider(Key<T> key) {
        return context.getProvider(Objects.requireNonNull(key, "key"));
    }

    @Override
    public Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        return context.getBeanDefinition(Objects.requireNonNull(key, "key"));
    }

    @Override
    public List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        return context.getBeanDefinitionsByStereotype(Objects.requireNonNull(stereotype, "stereotype"));
    }
}
