package be.imgn.inject.processor.spi;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * An injection site offered to an {@link InjectPlugin} — a field, or a constructor/method parameter.
 *
 * @param element the annotated field or parameter
 * @param beanType the type declaring the field, or owning the constructor/method
 * @param targetType the field's or parameter's declared type
 * @param processingEnvironment the current processing environment
 */
public record InjectionSite(
        Element element, TypeElement beanType, TypeMirror targetType, ProcessingEnvironment processingEnvironment) {}
