package be.imgn.inject.processor;

import java.util.List;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * The outcome of a {@link TestCompiler} run: whether it succeeded, every diagnostic, and every generated file.
 *
 * @param success whether javac reported no error
 * @param diagnostics every diagnostic, in reporting order
 * @param generatedFiles every generated file, named {@code /<LOCATION>/<relative path>}
 */
record Compilation(
        boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, List<JavaFileObject> generatedFiles) {

    Compilation {
        diagnostics = List.copyOf(diagnostics);
        generatedFiles = List.copyOf(generatedFiles);
    }

    /** {@return the diagnostics of {@code kind}} */
    List<Diagnostic<? extends JavaFileObject>> diagnostics(Diagnostic.Kind kind) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.getKind() == kind)
                .toList();
    }

    /** {@return the error diagnostics} */
    List<Diagnostic<? extends JavaFileObject>> errors() {
        return diagnostics(Diagnostic.Kind.ERROR);
    }

    /** {@return the generated source files} */
    List<JavaFileObject> generatedSourceFiles() {
        return generatedFiles.stream()
                .filter(file -> file.getName().startsWith("/" + StandardLocation.SOURCE_OUTPUT.getName() + "/"))
                .toList();
    }

    /** {@return the generated source of the top-level type {@code qualifiedName}, if any} */
    Optional<JavaFileObject> generatedSourceFile(String qualifiedName) {
        return generatedFile(StandardLocation.SOURCE_OUTPUT, qualifiedName.replace('.', '/') + ".java");
    }

    /** {@return the file generated at {@code relativePath} in {@code location}, if any} */
    Optional<JavaFileObject> generatedFile(JavaFileManager.Location location, String relativePath) {
        String name = "/" + location.getName() + "/" + relativePath;
        return generatedFiles.stream()
                .filter(file -> file.getName().equals(name))
                .findFirst();
    }
}
