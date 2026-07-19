package be.imgn.inject.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import be.imgn.inject.context.Factory;

/** Stage 4: registers the generated factories for {@code ServiceLoader} and notes the module-path directives. */
final class ServiceRegistrar {

    private static final String FACTORY_SERVICE = "META-INF/services/" + Factory.class.getName();

    private final ProcessingEnvironment environment;
    private final Diagnostics diagnostics;

    ServiceRegistrar(ProcessingEnvironment environment, Diagnostics diagnostics) {
        this.environment = environment;
        this.diagnostics = diagnostics;
    }

    void register(List<String> factoryNames) {
        if (factoryNames.isEmpty()) {
            return;
        }
        if (serviceFileExistsOnSourcePath()) {
            diagnostics.error("A hand-authored " + FACTORY_SERVICE
                    + " exists on the source path; the processor owns this file. Remove it.");
            return;
        }
        try {
            FileObject file = environment.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", FACTORY_SERVICE);
            try (Writer writer = file.openWriter()) {
                for (String name : factoryNames) {
                    writer.write(name);
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            diagnostics.error("Failed to write factory service file: " + e.getMessage());
        }
        String directives = "For the module path, declare in module-info.java:\n    provides " + Factory.class.getName()
                + " with\n        " + String.join(",\n        ", factoryNames) + ";";
        diagnostics.note(directives);
    }

    private boolean serviceFileExistsOnSourcePath() {
        try {
            environment
                    .getFiler()
                    .getResource(StandardLocation.SOURCE_PATH, "", FACTORY_SERVICE)
                    .openInputStream()
                    .close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
