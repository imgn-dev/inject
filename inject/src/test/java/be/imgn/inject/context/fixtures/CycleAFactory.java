package be.imgn.inject.context.fixtures;

import java.util.List;
import java.util.Optional;

import be.imgn.inject.context.AnnotationMetadata;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.Parameter;

/** Half of a two-bean cycle: A depends on B. */
public final class CycleAFactory extends Factory<CycleAFactory.A> {
    /** Bean A. */
    public record A(CycleBFactory.B b) {}

    public CycleAFactory() {
        super(A.class);
    }

    @Override
    protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
        return Optional.of(new ConstructorInjectionPoint(
                this, List.of(new Parameter("b", CycleBFactory.B.class)), AnnotationMetadata.empty()));
    }

    @Override
    protected A createInstance(Context context) {
        return new A(getInstanceForConstructor(0, context));
    }
}
