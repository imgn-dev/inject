package be.imgn.inject.context.fixtures;

import be.imgn.inject.context.AnnotationMetadata;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.Parameter;
import java.util.List;
import java.util.Optional;

/** Half of a two-bean cycle: B depends on A. */
public final class CycleBFactory extends Factory<CycleBFactory.B> {
    public CycleBFactory() {
        super(B.class);
    }

    @Override
    protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
        return Optional.of(new ConstructorInjectionPoint(
                this, List.of(new Parameter("a", CycleAFactory.A.class)), AnnotationMetadata.empty()));
    }

    @Override
    protected B createInstance(Context context) {
        return new B(this.<CycleAFactory.A>getInstanceForConstructor(0, CycleAFactory.A.class, context));
    }

    /** Bean B. */
    public record B(CycleAFactory.A a) {}
}
