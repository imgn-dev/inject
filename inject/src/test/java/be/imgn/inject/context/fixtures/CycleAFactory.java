package be.imgn.inject.context.fixtures;

import java.util.List;

import be.imgn.inject.Key;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

/** Half of a two-bean cycle: A depends on B. */
public final class CycleAFactory extends Factory<CycleAFactory.A> {
    /** Bean A. */
    public record A(CycleBFactory.B b) {}

    public CycleAFactory() {
        super(A.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(CycleBFactory.B.class)));
    }

    @Override
    protected A createInstance(Context context) {
        return new A(getInstanceForConstructor(0, context));
    }
}
