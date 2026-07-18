package be.imgn.inject.context.fixtures;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

import be.imgn.inject.Singleton;
import be.imgn.inject.context.AnnotationMetadata;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.Parameter;

/** Singleton-scoped bean depending on the leaf {@code String}. */
public final class RootFactory extends Factory<RootFactory.Root> {
    /** The produced bean. */
    public record Root(String leaf) {}

    public RootFactory() {
        super(Root.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(
                this, List.of(new Parameter("leaf", String.class)), AnnotationMetadata.empty());
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
