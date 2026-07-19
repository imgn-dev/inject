package be.imgn.inject.processor.spi;

import java.util.Optional;

import com.palantir.javapoet.CodeBlock;

/**
 * Extension point for the annotation processor, discovered via {@link java.util.ServiceLoader}. A plugin handles fields
 * annotated with an annotation meta-annotated {@link be.imgn.inject.InjectingAnnotation} and supplies the expression
 * used to initialize them — enabling injection of values the container cannot otherwise provide (e.g. a logger).
 */
public interface InjectPlugin {

    /**
     * {@return an initializer expression for the given injection site, or empty to decline it}
     *
     * @param site the field injection site to consider
     */
    Optional<CodeBlock> fieldInitializer(InjectionSite site);
}
