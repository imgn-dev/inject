package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.CodeBlock;
import java.util.List;

/**
 * An {@code @Inject} method to generate injection for.
 *
 * @param name the method name
 * @param parameters the method parameters, in order
 * @param metadata an {@code AnnotationMetadata} expression for the method's framework-related annotations
 */
record MethodModel(String name, List<ParameterModel> parameters, CodeBlock metadata) {}
