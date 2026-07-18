package be.imgn.inject.context.fixtures;

import java.util.List;

import be.imgn.inject.context.AnnotationMetadata;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.Parameter;

/** Half of a two-bean cycle: B depends on A. */
public final class CycleBFactory extends Factory<CycleBFactory.B> {
    /** Bean B. */
    public record B(CycleAFactory.A a) {}

    public CycleBFactory() {
        super(B.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(
                this, List.of(new Parameter("a", CycleAFactory.A.class)), AnnotationMetadata.empty());
    }

    @Override
    protected B createInstance(Context context) {
        return new B(getInstanceForConstructor(0, context));
    }
}
