package be.imgn.inject.context.fixtures;

import java.util.List;

import be.imgn.inject.Key;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

/** Half of a two-bean cycle: B depends on A. */
public final class CycleBFactory extends Factory<CycleBFactory.B> {
    /** Bean B. */
    public record B(CycleAFactory.A a) {}

    public CycleBFactory() {
        super(B.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(CycleAFactory.A.class)));
    }

    @Override
    protected B createInstance(Context context) {
        return new B(getInstanceForConstructor(0, context));
    }
}
