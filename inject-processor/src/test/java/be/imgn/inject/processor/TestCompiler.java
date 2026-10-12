package be.imgn.inject.processor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Runs the system Java compiler in process over in-memory sources, with explicit annotation processors, on the test
 * classpath. Generated files are written to temporary directories, read back into the {@link Compilation}, and the
 * directories are deleted.
 */
final class TestCompiler {

    private final List<Processor> processors;
    private final List<String> options;

    private TestCompiler(List<Processor> processors, List<String> options) {
        this.processors = processors;
        this.options = options;
    }

    static TestCompiler javac() {
        return new TestCompiler(List.of(), List.of());
    }

    /** {@return an in-memory compilation unit for the top-level type {@code qualifiedName}} */
    static JavaFileObject source(String qualifiedName, String text) {
        URI uri = URI.create("mem:///" + qualifiedName.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    TestCompiler withProcessors(Processor... processors) {
        return new TestCompiler(List.of(processors), options);
    }

    /** {@return a compiler passing {@code options} to javac, after {@code -proc:full}} */
    TestCompiler withOptions(String... options) {
        return new TestCompiler(processors, List.of(options));
    }

    Compilation compile(JavaFileObject... sources) {
        return compile(List.of(sources));
    }

    Compilation compile(List<JavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classOutput = createTempDirectory("class-output");
        Path sourceOutput = createTempDirectory("source-output");
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutput));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourceOutput));
            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, diagnostics, javacOptions(), null, sources);
            task.setProcessors(processors);
            boolean success = task.call();
            List<JavaFileObject> generated = new ArrayList<>();
            generated.addAll(readBack(StandardLocation.CLASS_OUTPUT, classOutput));
            generated.addAll(readBack(StandardLocation.SOURCE_OUTPUT, sourceOutput));
            return new Compilation(success, diagnostics.getDiagnostics(), generated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(classOutput);
            deleteRecursively(sourceOutput);
        }
    }

    private List<String> javacOptions() {
        List<String> all = new ArrayList<>();
        all.add("-proc:full");
        all.addAll(options);
        return all;
    }

    private static List<JavaFileObject> readBack(StandardLocation location, Path root) throws IOException {
        List<JavaFileObject> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path)
                        .toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                files.add(new GeneratedFile(location.getName() + "/" + relative, Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory("inject-processor-test-" + prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file the compilation generated, held in memory and named {@code /<LOCATION>/<relative path>}. */
    static final class GeneratedFile extends SimpleJavaFileObject {
        private final byte[] content;

        GeneratedFile(String path, byte[] content) {
            super(URI.create("mem:///" + path), kindOf(path));
            this.content = content.clone();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return new String(content, StandardCharsets.UTF_8);
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(content);
        }

        private static Kind kindOf(String path) {
            for (Kind kind : List.of(Kind.SOURCE, Kind.CLASS, Kind.HTML)) {
                if (path.endsWith(kind.extension)) {
                    return kind;
                }
            }
            return Kind.OTHER;
        }
    }
}
