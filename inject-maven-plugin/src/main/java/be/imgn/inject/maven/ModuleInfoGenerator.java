package be.imgn.inject.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Creates a {@code module-info.class} for a project that has none, so its generated factories and any custom scopes are
 * discoverable on the module path.
 *
 * <p>The descriptor requires {@code be.imgn.inject}, exports every package holding a compiled class, and provides each
 * framework service's registered providers. It is written to the compiled output only — the project's sources are never
 * touched — so a project that later adds its own {@code module-info.java} simply takes over.
 */
final class ModuleInfoGenerator {

    private static final String INJECT_MODULE = "be.imgn.inject";

    private ModuleInfoGenerator() {}

    /**
     * Writes a {@code module-info.class} describing {@code moduleName} into {@code classesDirectory}.
     *
     * @param classesDirectory the compiled output directory
     * @param moduleName the module name to declare
     * @param providers each framework service mapped to its provider class names
     * @throws IOException if the output cannot be scanned or written
     */
    static void generate(Path classesDirectory, String moduleName, Map<String, List<String>> providers)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule(moduleName, 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        module.visitRequire(INJECT_MODULE, 0, null);
        for (String packageName : packagesIn(classesDirectory)) {
            module.visitExport(packageName.replace('.', '/'), 0);
        }
        providers.forEach((service, impls) -> module.visitProvide(
                service.replace('.', '/'),
                impls.stream().map(name -> name.replace('.', '/')).toArray(String[]::new)));
        module.visitEnd();
        writer.visitEnd();
        Files.write(classesDirectory.resolve("module-info.class"), writer.toByteArray());
    }

    /** {@return the packages under {@code classesDirectory} that hold at least one class file} */
    private static Set<String> packagesIn(Path classesDirectory) throws IOException {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".class"))
                    .filter(file -> !file.getFileName().toString().equals("module-info.class"))
                    .map(file -> classesDirectory.relativize(file).getParent())
                    .filter(parent -> parent != null)
                    .map(parent -> parent.toString().replace(File.separatorChar, '.'))
                    .forEach(packages::add);
        }
        return packages;
    }
}
