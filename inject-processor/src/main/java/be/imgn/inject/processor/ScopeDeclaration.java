package be.imgn.inject.processor;

import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.source.ClassName;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

/**
 * A bean's scoping annotation and the type that declares it: the bean itself, or a supertype it inherits the scope
 * from.
 *
 * @param annotation the scoping annotation
 * @param declaringType the type the annotation is declared on
 */
record ScopeDeclaration(AnnotationMirror annotation, TypeElement declaringType) {

    /** {@return the scoping annotation's type} */
    TypeElement annotationType() {
        return MoreElements.asType(annotation.getAnnotationType().asElement());
    }

    /** {@return the scoping annotation's class name} */
    ClassName type() {
        return ClassName.of(annotationType());
    }
}
