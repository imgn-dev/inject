package be.imgn.inject.processor;

import be.imgn.gaia.processing.Diagnostics;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.source.ClassName;
import be.imgn.inject.context.Factory;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.tools.StandardLocation;

/**
 * Stage 4: registers the generated factories for {@code ServiceLoader} through the context's service files, and notes
 * the module-path directives.
 */
final class ServiceRegistrar {

    private static final ClassName FACTORY = ClassName.of(Factory.class);
    private static final String FACTORY_SERVICE = "META-INF/services/" + Factory.class.getName();

    private final ProcessingContext context;
    private final Diagnostics diagnostics;
    private final Set<ClassName> factories = new LinkedHashSet<>();

    ServiceRegistrar(ProcessingContext context) {
        this.context = context;
        this.diagnostics = context.diagnostics();
        // A named module declares its factories itself, or inject-maven-plugin adds them to module-info.class after
        // compilation: a module-info.java without the provides directives is legitimate.
        context.services().skipModuleCheck(FACTORY);
    }

    /** Registers a generated factory, to list in the service file. */
    void register(ClassName factory, Element origin) {
        factories.add(factory);
        context.services().add(FACTORY, factory, origin);
    }

    /**
     * Notes the module-path directives after the last round, before the service files are written; withdraws the
     * service file instead when a hand-authored one exists on the source path.
     */
    void finish() {
        if (factories.isEmpty()) {
            return;
        }
        if (serviceFileExistsOnSourcePath()) {
            context.services().discard(FACTORY);
            diagnostics.error("A hand-authored " + FACTORY_SERVICE
                    + " exists on the source path; the processor owns this file. Remove it.");
            return;
        }
        String directives = "For the module path, declare in module-info.java:\n    provides " + Factory.class.getName()
                + " with\n        "
                + String.join(
                        ",\n        ",
                        factories.stream().map(ClassName::canonicalName).toList())
                + ";";
        diagnostics.note(directives);
    }

    private boolean serviceFileExistsOnSourcePath() {
        try {
            context.filer()
                    .getResource(StandardLocation.SOURCE_PATH, "", FACTORY_SERVICE)
                    .openInputStream()
                    .close();
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
