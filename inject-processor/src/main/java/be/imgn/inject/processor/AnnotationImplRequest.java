package be.imgn.inject.processor;

import javax.lang.model.element.TypeElement;

/**
 * A request to emit an annotation implementation class.
 *
 * @param annotationType the annotation to implement
 * @param targetPackage the package to emit it into: the using factory's package, not the annotation's own
 */
record AnnotationImplRequest(TypeElement annotationType, String targetPackage) {}
