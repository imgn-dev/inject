package be.imgn.inject.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/** Reports compile-time errors and notes through the processing environment's messager. */
final class Diagnostics {

    private final ProcessingEnvironment environment;

    Diagnostics(ProcessingEnvironment environment) {
        this.environment = environment;
    }

    void error(String message, Element element) {
        environment.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    void error(String message) {
        environment.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }

    void note(String message) {
        environment.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
    }
}
