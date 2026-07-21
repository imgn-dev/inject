package be.imgn.inject.context;

import java.util.List;

import be.imgn.inject.Key;

/**
 * A read-only view of a registered bean: its primary key, the framework annotations declared on it, and its injection
 * points. Obtained from an {@link be.imgn.inject.Injector} or {@link Context} for inspection — tooling, tests, or a
 * caller reasoning about the graph — never to build instances.
 */
public interface BeanDefinition {

    /** {@return the bean's primary (unqualified) key} */
    Key<?> key();

    /** {@return the framework annotations declared on the bean itself} */
    AnnotationMetadata annotationMetadata();

    /** {@return the bean's injection points: its constructor (when it injects), then methods, then fields} */
    List<InjectionPoint> injectionPoints();
}
