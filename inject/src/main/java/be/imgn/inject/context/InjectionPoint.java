package be.imgn.inject.context;

/** Metadata describing where dependencies are injected, for inspection and resolution. */
public sealed interface InjectionPoint permits ConstructorInjectionPoint, FieldInjectionPoint, MethodInjectionPoint {
    /** {@return the factory owning this injection point} */
    Factory<?> factory();

    /** {@return the annotations present at this injection point} */
    AnnotationMetadata annotationMetadata();
}
