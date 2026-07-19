package be.imgn.inject.processor.spi;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * A field injection site offered to an {@link InjectPlugin}.
 *
 * @param element the annotated field
 * @param beanType the type declaring the field
 * @param fieldType the field's declared type
 * @param processingEnvironment the current processing environment
 */
public record InjectionSite(
        Element element, TypeElement beanType, TypeMirror fieldType, ProcessingEnvironment processingEnvironment) {}
