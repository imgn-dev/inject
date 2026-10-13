package be.imgn.inject.processor.spi;

import be.imgn.gaia.processing.source.CodeBlock;
import java.util.Optional;

/**
 * Extension point for the annotation processor, discovered via {@link java.util.ServiceLoader}. A plugin handles fields
 * and constructor/method parameters annotated with an annotation meta-annotated
 * {@link be.imgn.inject.InjectingAnnotation} and supplies the expression used to initialize them — enabling injection
 * of values the container cannot otherwise provide (e.g. a logger).
 */
public interface InjectPlugin {

    /**
     * Returns an initializer expression for the given injection site, or empty to decline it.
     *
     * @param site the field or parameter injection site to consider
     * @return an initializer expression for the given injection site, or empty to decline it
     */
    Optional<CodeBlock> initializer(InjectionSite site);
}
