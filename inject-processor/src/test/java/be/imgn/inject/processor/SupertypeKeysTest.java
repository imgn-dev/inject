package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import be.imgn.inject.Injector;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupertypeKeysTest {
    private static final List<JavaFileObject> VIEWS = List.of(
            TestCompiler.source("example.View", """
                    package example;

                    public interface View {}
                    """),
            TestCompiler.source("example.DialogView", """
                    package example;

                    public interface DialogView<R> extends View {}
                    """),
            TestCompiler.source("example.Editor", """
                    package example;

                    import be.imgn.inject.Inject;

                    public class Editor implements View {
                        @Inject
                        public Editor() {}
                    }
                    """),
            TestCompiler.source("example.RenameDialog", """
                    package example;

                    import be.imgn.inject.Inject;

                    public class RenameDialog implements DialogView<String> {
                        @Inject
                        public RenameDialog() {}
                    }
                    """),
            TestCompiler.source("example.ConfirmDialog", """
                    package example;

                    import be.imgn.inject.Inject;

                    public class ConfirmDialog implements DialogView<Boolean> {
                        @Inject
                        public ConfirmDialog() {}
                    }
                    """));

    @Test
    void aBeanDeclaresTheKeysItHasThroughItsSupertypes() {
        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEWS);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$RenameDialog$$Factory")
                .contains("protected List<Key<?>> supertypeKeys()")
                .contains("return List.of(Key.of(DialogView.class), Key.of(View.class))");
    }

    @Test
    void keysChosenWithExposeAsAreNotSupertypeKeys() {
        JavaFileObject console = TestCompiler.source("example.Console", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs(View.class)
                public class Console implements View {
                    @Inject
                    public Console() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEWS.getFirst(), console);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Console$$Factory").doesNotContain("supertypeKeys");
    }

    @Test
    void severalViewsSharingTheirInterfacesMakeAValidInjector(@TempDir Path classes) throws Exception {
        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEWS);
        assertThat(compilation).succeeded();
        writeClasses(compilation, classes);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Injector injector = Injector.create();
                Assertions.assertThat(injector.getInstance(loader.loadClass("example.RenameDialog")))
                        .isNotNull();
                Class<?> view = loader.loadClass("example.View");
                assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                        .isThrownBy(() -> injector.getInstance(view))
                        .withMessageStartingWith("No unique binding for Key[example.View]: implemented by");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
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
