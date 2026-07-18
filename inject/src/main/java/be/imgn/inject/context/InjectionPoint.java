package be.imgn.inject.context;

import java.util.List;

import be.imgn.inject.Key;

/** Metadata describing where dependencies are injected, for inspection and resolution. */
public sealed interface InjectionPoint permits ConstructorInjectionPoint, MethodInjectionPoint, FieldInjectionPoint {
    /** {@return the keys this injection point depends on, in declaration order} */
    List<Key<?>> keys();
}
