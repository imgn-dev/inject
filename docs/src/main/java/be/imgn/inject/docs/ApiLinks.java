package be.imgn.inject.docs;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Turns the name of an inject class into a link to its page in the API documentation, published next to the guide in
 * {@code api/latest/}.
 */
final class ApiLinks {

    static final String SCHEME = "api:";

    private static final String MODULE = "be.imgn.inject";
    private static final String BASE = "api/latest/";

    private ApiLinks() {}

    /**
     * Returns the link to the API documentation of a class, named as in Java source, such as
     * {@code be.imgn.inject.Injector.Builder}.
     *
     * @return the link, or an empty optional if the name is not a public class of a package that an inject module
     *     exports, the only ones the API documentation has
     */
    static Optional<String> href(String className) {
        return load(className)
                .filter(ApiLinks::isPublic)
                .flatMap(type -> module(type)
                        .filter(module -> isExported(module, type.getPackageName()))
                        .map(module -> BASE
                                + module.name()
                                + "/"
                                + type.getPackageName().replace('.', '/')
                                + "/"
                                + type.getCanonicalName()
                                        .substring(type.getPackageName().length() + 1)
                                + ".html"));
    }

    private static boolean isPublic(Class<?> type) {
        for (Class<?> t = type; t != null; t = t.getEnclosingClass()) {
            if (!Modifier.isPublic(t.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExported(ModuleDescriptor module, String packageName) {
        return module.exports().stream()
                .anyMatch(exports -> !exports.isQualified() && exports.source().equals(packageName));
    }

    private static boolean isInjectModule(String name) {
        return name.equals(MODULE) || name.startsWith(MODULE + ".");
    }

    // A nested class has a binary name with $ where the canonical name has dots: tries each dot from the right.
    private static Optional<Class<?>> load(String className) {
        var binaryName = className;
        while (true) {
            try {
                return Optional.of(Class.forName(binaryName, false, ApiLinks.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                var dot = binaryName.lastIndexOf('.');
                if (dot < 0) {
                    return Optional.empty();
                }
                binaryName = binaryName.substring(0, dot) + "$" + binaryName.substring(dot + 1);
            }
        }
    }

    // The generator runs on the class path, where every class is in the unnamed module: the module is the one whose
    // jar or directory the class was loaded from.
    private static Optional<ModuleDescriptor> module(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null) {
            return Optional.empty();
        }
        try {
            return ModuleFinder.of(Path.of(source.getLocation().toURI())).findAll().stream()
                    .map(ModuleReference::descriptor)
                    .filter(descriptor -> isInjectModule(descriptor.name()))
                    .findFirst();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate " + source.getLocation(), e);
        }
    }
}
