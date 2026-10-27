package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InheritedScopeTest {

    private static final JavaFileObject VIEW_SCOPED = TestCompiler.source("example.ViewScoped", """
            package example;

            import be.imgn.inject.ScopingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @ScopingAnnotation
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface ViewScoped {}
            """);

    private static final JavaFileObject VIEW = TestCompiler.source("example.View", """
            package example;

            @ViewScoped
            public interface View {}
            """);

    @Test
    void beanInheritsTheScopeOfAnInterface() {
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;

                public class Editor implements View {
                    @Inject
                    public Editor() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, editor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Editor$$Factory")
                .contains("return Optional.of(ViewScoped.class)")
                .contains("AnnotationValue.of(ViewScoped.class");
    }

    @Test
    void beanInheritsTheScopeThroughSuperclassesAndSuperinterfaces() {
        JavaFileObject dialog = TestCompiler.source("example.DialogView", """
                package example;

                public interface DialogView extends View {}
                """);
        JavaFileObject base = TestCompiler.source("example.BaseDialog", """
                package example;

                public abstract class BaseDialog implements DialogView {}
                """);
        JavaFileObject rename = TestCompiler.source("example.RenameDialog", """
                package example;

                import be.imgn.inject.Inject;

                public class RenameDialog extends BaseDialog {
                    @Inject
                    public RenameDialog() {}
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, dialog, base, rename);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$RenameDialog$$Factory")
                .contains("return Optional.of(ViewScoped.class)");
    }

    @Test
    void aScopeDeclaredOnTheBeanWins() {
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Editor implements View {
                    @Inject
                    public Editor() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, editor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Editor$$Factory")
                .contains("return Optional.of(Singleton.class)")
                .doesNotContain("ViewScoped");
    }

    @Test
    void theNearestDeclarationWinsAlongAPath() {
        JavaFileObject dialog = TestCompiler.source("example.DialogView", """
                package example;

                import be.imgn.inject.Singleton;

                @Singleton
                public interface DialogView extends View {}
                """);
        JavaFileObject rename = TestCompiler.source("example.RenameDialog", """
                package example;

                import be.imgn.inject.Inject;

                public class RenameDialog implements DialogView {
                    @Inject
                    public RenameDialog() {}
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, dialog, rename);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$RenameDialog$$Factory")
                .contains("return Optional.of(Singleton.class)");
    }

    @Test
    void theSameScopeAlongTwoPathsIsNoConflict() {
        JavaFileObject panel = TestCompiler.source("example.Panel", """
                package example;

                @ViewScoped
                public interface Panel {}
                """);
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;

                public class Editor implements View, Panel {
                    @Inject
                    public Editor() {}
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, panel, editor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Editor$$Factory")
                .contains("return Optional.of(ViewScoped.class)");
    }

    @Test
    void twoDifferentInheritedScopesAreAnError() {
        JavaFileObject panel = TestCompiler.source("example.Panel", """
                package example;

                import be.imgn.inject.Singleton;

                @Singleton
                public abstract class Panel {}
                """);
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;

                public class Editor extends Panel implements View {
                    @Inject
                    public Editor() {}
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, panel, editor);

        assertThat(compilation)
                .failed()
                .hadErrorContaining("Editor inherits two scopes: @Singleton from Panel and @ViewScoped from View."
                        + " Declare one on Editor.");
    }

    @Test
    void preDestroyIsAllowedWithAnInheritedScope() {
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PreDestroy;

                public class Editor implements View {
                    @Inject
                    public Editor() {}

                    @PreDestroy
                    void release() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, editor);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Editor$$Factory").contains("instance.release()");
    }

    @Test
    void anAssistedRecipeCannotInheritAScope() {
        JavaFileObject rename = TestCompiler.source("example.RenameDialog", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class RenameDialog implements View {
                    @Inject
                    public RenameDialog(@Assisted String current) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(VIEW_SCOPED, VIEW, rename);

        assertThat(compilation)
                .failed()
                .hadErrorContaining("An assisted recipe cannot be scoped; every factory call builds a fresh instance."
                        + " RenameDialog inherits @ViewScoped from View.");
    }

    @Test
    void beanInheritsAScopeReadFromAClassFile(@TempDir Path classes) throws IOException {
        Compilation library = javac().compile(VIEW_SCOPED, VIEW);
        assertThat(library).succeeded();
        writeClasses(library, classes);
        JavaFileObject editor = TestCompiler.source("example.Editor", """
                package example;

                import be.imgn.inject.Inject;

                public class Editor implements View {
                    @Inject
                    public Editor() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .withOptions("-classpath", classes + File.pathSeparator + System.getProperty("java.class.path"))
                .compile(editor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Editor$$Factory")
                .contains("return Optional.of(ViewScoped.class)");
    }

    private static void writeClasses(Compilation compilation, Path directory) throws IOException {
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
