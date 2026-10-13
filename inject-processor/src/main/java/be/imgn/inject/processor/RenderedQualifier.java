package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.CodeBlock;

/**
 * A generated qualifier expression together with how it keys a binding.
 *
 * @param expression the Java expression: a {@code Class} literal for a marker, or an annotation-instance expression
 *     (for example {@code Names.named("x")} or {@code new Foo__Impl(...)}) otherwise
 * @param marker whether the qualifier is a marker (matched by annotation type) rather than an instance (matched by
 *     annotation equality)
 */
record RenderedQualifier(CodeBlock expression, boolean marker) {}
