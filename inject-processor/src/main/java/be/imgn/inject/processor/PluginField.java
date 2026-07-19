package be.imgn.inject.processor;

import com.palantir.javapoet.CodeBlock;

/**
 * A field initialized by a plugin (via an {@code @InjectingAnnotation}) rather than resolved from the context.
 *
 * @param name the field name
 * @param initializer the expression assigned to the field
 */
record PluginField(String name, CodeBlock initializer) {}
