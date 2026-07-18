package be.imgn.inject.context.fixtures;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

import be.imgn.inject.Key;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

/** Singleton-scoped bean depending on the leaf {@code String}. */
public final class RootFactory extends Factory<RootFactory.Root> {
    /** The produced bean. */
    public record Root(String leaf) {}

    public RootFactory() {
        super(Root.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(String.class)));
    }

    @Override
    protected Optional<Class<? extends Annotation>> scope() {
        return Optional.of(Singleton.class);
    }

    @Override
    protected Root createInstance(Context context) {
        return new Root(getInstanceForConstructor(0, context));
    }
}
