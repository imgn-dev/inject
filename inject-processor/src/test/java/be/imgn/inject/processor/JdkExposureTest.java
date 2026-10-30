package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import be.imgn.inject.Injector;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkExposureTest {
    private static final JavaFileObject VIEW = TestCompiler.source("example.View", """
            package example;

            public interface View {}
            """);

    @Test
    void aJdkSuperclassAndWhatItInheritsAreNotExposed() {
        JavaFileObject scene = TestCompiler.source("example.Scene", """
                package example;

                import be.imgn.inject.Inject;
                import javax.swing.JPanel;

                public class Scene extends JPanel implements View {
                    @Inject
                    public Scene() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEW, scene);

        assertThat(compilation)
                .succeeded()
                .generatedSourceFile("example.$Scene$$Factory")
                .contains("return List.of(Key.of(Scene.class), Key.of(View.class))")
                .contains("return List.of(Key.of(View.class))")
                .doesNotContain("JPanel.class")
                .doesNotContain("JComponent")
                .doesNotContain("Accessible");
    }

    @Test
    void aJdkInterfaceTheBeanImplementsStaysExposed() {
        JavaFileObject source = TestCompiler.source("example.TicketSource", """
                package example;

                import be.imgn.inject.Inject;
                import java.util.function.Supplier;

                public class TicketSource implements Supplier<String> {
                    @Inject
                    public TicketSource() {}

                    @Override
                    public String get() {
                        return "ticket";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(source);

        assertThat(compilation)
                .succeeded()
                .generatedSourceFile("example.$TicketSource$$Factory")
                .contains("return List.of(Key.of(TicketSource.class), Key.of(Supplier.class))");
    }

    @Test
    void aJdkInterfaceOneOfTheBeansInterfacesExtendsStaysExposedWithoutItsOwnSupertypes() {
        JavaFileObject store = TestCompiler.source("example.Store", """
                package example;

                import java.util.function.Supplier;

                public interface Store extends Supplier<String>, AutoCloseable {}
                """);
        JavaFileObject memory = TestCompiler.source("example.MemoryStore", """
                package example;

                import be.imgn.inject.Inject;

                public class MemoryStore implements Store {
                    @Inject
                    public MemoryStore() {}

                    @Override
                    public String get() {
                        return "";
                    }

                    @Override
                    public void close() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(store, memory);

        assertThat(compilation)
                .succeeded()
                .generatedSourceFile("example.$MemoryStore$$Factory")
                .contains("return List.of(Key.of(MemoryStore.class), Key.of(Store.class), Key.of(Supplier.class))");
    }

    @Test
    void exposeAsStillExposesAJdkSuperclass() {
        JavaFileObject scene = TestCompiler.source("example.Scene", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import javax.swing.JPanel;

                @ExposeAs({Scene.class, JPanel.class})
                public class Scene extends JPanel {
                    @Inject
                    public Scene() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(scene);

        assertThat(compilation)
                .succeeded()
                .generatedSourceFile("example.$Scene$$Factory")
                .contains("return List.of(Key.of(Scene.class), Key.of(JPanel.class))");
    }

    @Test
    void twoBeansWithAJdkSuperclassClaimNoJdkKey(@TempDir Path classes) throws Exception {
        List<JavaFileObject> sources = List.of(VIEW, writerView("LogView"), writerView("TraceView"));
        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(sources);
        assertThat(compilation).succeeded();
        writeClasses(compilation, classes);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Injector injector = Injector.create();
                Assertions.assertThat(injector.getInstance(loader.loadClass("example.LogView")))
                        .isNotNull();
                assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                        .isThrownBy(() -> injector.getInstance(Writer.class))
                        .withMessage("No binding for Key[java.io.Writer]");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static JavaFileObject writerView(String name) {
        return TestCompiler.source("example." + name, """
                package example;

                import be.imgn.inject.Inject;
                import java.io.StringWriter;

                public class %s extends StringWriter implements View {
                    @Inject
                    public %s() {}
                }
                """.formatted(name, name));
    }

    private static void writeClasses(Compilation compilation, Path directory) throws Exception {
        String marker = "CLASS_OUTPUT/";
        for (JavaFileObject file : compilation.generatedFiles()) {
            String name = file.getName();
            int index = name.indexOf(marker);
            if (index < 0) {
                continue;
            }
            Path target = directory.resolve(name.substring(index + marker.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = file.openInputStream()) {
                Files.write(target, in.readAllBytes());
            }
        }
    }
}
