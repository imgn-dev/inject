package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.CompilationAssert;
import be.imgn.gaia.processing.testing.TestCompiler;
import be.imgn.inject.Injector;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles a dialog whose assisted recipe inherits a child-local scope, and runs it: the product is cached in the host
 * it is created through, and resolvable there once created.
 */
class ScopedAssistedEndToEndTest {
    private static final List<JavaFileObject> SOURCES = List.of(
            scope("example.WindowScoped"),
            scope("example.ViewScoped"),
            TestCompiler.source("example.DialogView", """
                    package example;

                    @ViewScoped
                    public interface DialogView {}
                    """),
            TestCompiler.source("example.RenameDialog", """
                    package example;

                    import be.imgn.inject.Assisted;
                    import be.imgn.inject.Inject;
                    import be.imgn.inject.PreDestroy;
                    import java.util.ArrayList;
                    import java.util.List;

                    public class RenameDialog implements DialogView {
                        public static final List<String> CLOSED = new ArrayList<>();

                        private final String current;
                        private final RenameDialogCommands commands;

                        @Inject
                        public RenameDialog(@Assisted String current, RenameDialogCommands commands) {
                            this.current = current;
                            this.commands = commands;
                        }

                        public String current() {
                            return current;
                        }

                        public RenameDialogCommands commands() {
                            return commands;
                        }

                        @PreDestroy
                        void close() {
                            CLOSED.add(current);
                        }
                    }
                    """),
            TestCompiler.source("example.RenameDialogCommands", """
                    package example;

                    import be.imgn.inject.Inject;
                    import be.imgn.inject.Provider;

                    @ViewScoped
                    public class RenameDialogCommands {
                        private final Provider<RenameDialog> dialog;

                        @Inject
                        public RenameDialogCommands(Provider<RenameDialog> dialog) {
                            this.dialog = dialog;
                        }

                        public Provider<RenameDialog> dialog() {
                            return dialog;
                        }
                    }
                    """),
            TestCompiler.source("example.Recorder", """
                    package example;

                    import be.imgn.inject.ConstructionListener;
                    import be.imgn.inject.Inject;
                    import be.imgn.inject.Injector;
                    import be.imgn.inject.IntoSet;
                    import be.imgn.inject.Singleton;
                    import java.util.ArrayList;
                    import java.util.List;

                    @Singleton
                    @IntoSet(ConstructionListener.class)
                    public class Recorder implements ConstructionListener {
                        public static final List<Object> INSTANCES = new ArrayList<>();
                        public static final List<Injector> INJECTORS = new ArrayList<>();

                        @Inject
                        public Recorder() {}

                        @Override
                        public void constructed(Object instance, Injector injector) {
                            INSTANCES.add(instance);
                            INJECTORS.add(injector);
                        }
                    }
                    """),
            TestCompiler.source("example.RenameDialogFactory", """
                    package example;

                    import be.imgn.inject.AssistedFactory;

                    public interface RenameDialogFactory {
                        @AssistedFactory
                        RenameDialog create(String current);
                    }
                    """));

    private URLClassLoader loader;
    private ClassLoader previous;
    private Injector root;

    @BeforeEach
    void compileAndCreateTheRoot(@TempDir Path classes) throws Exception {
        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(SOURCES);
        CompilationAssert.assertThat(compilation).succeeded();
        writeClasses(compilation, classes);
        loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        root = Injector.create();
    }

    @AfterEach
    void restoreTheClassLoader() throws Exception {
        Thread.currentThread().setContextClassLoader(previous);
        loader.close();
    }

    @Test
    void theProductIsCachedInItsHostAndResolvableThroughItAndItsDescendants() throws Exception {
        Injector view = dialogView();
        Object dialog = create(view, "draft");

        assertThat(view.getInstance(type("example.RenameDialog")))
                .isSameAs(dialog)
                .isSameAs(view.childBuilder().build().getInstance(type("example.RenameDialog")));
        Object commands = call(dialog, "commands");
        assertThat(((Provider<?>) call(commands, "dialog")).get()).isSameAs(dialog);
        assertThat(call(dialog, "current")).isEqualTo("draft");
    }

    @Test
    void resolvingTheProductBeforeItIsCreatedFails() throws Exception {
        Injector view = dialogView();
        Provider<?> dialog = view.getProvider(type("example.RenameDialog"));

        assertThatIllegalStateException()
                .isThrownBy(dialog::get)
                .withMessage("Key[example.RenameDialog] has not been created in this @ViewScoped injector yet: it is"
                        + " created by example.RenameDialogFactory.create(...).");
    }

    @Test
    void aSecondCreationThroughTheSameHostFails() throws Exception {
        Injector view = dialogView();
        create(view, "first");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> create(view, "second"))
                .havingCause()
                .isInstanceOf(IllegalStateException.class)
                .withMessage("example.RenameDialogFactory.create(...) was already called through this @ViewScoped"
                        + " injector. Key[example.RenameDialog] is @ViewScoped, so it exists once per injector; create"
                        + " a new @ViewScoped injector for another one.");
    }

    @Test
    void eachHostCreatesItsOwnProduct() throws Exception {
        Object first = create(dialogView(), "first");
        Object second = create(dialogView(), "second");

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void closingTheHostDestroysTheProduct() throws Exception {
        Injector view = dialogView();
        create(view, "closed");
        @SuppressWarnings("unchecked")
        List<String> closed =
                (List<String>) type("example.RenameDialog").getField("CLOSED").get(null);

        view.close();

        assertThat(closed).containsExactly("closed");
    }

    @Test
    void theProductIsReportedWithItsHost() throws Exception {
        Injector view = dialogView();
        Object dialog = create(view, "reported");
        List<Object> instances = new ArrayList<>(
                (List<?>) type("example.Recorder").getField("INSTANCES").get(null));
        List<?> injectors =
                (List<?>) type("example.Recorder").getField("INJECTORS").get(null);

        assertThat(instances).contains(dialog, call(dialog, "commands"));
        assertThat(injectors.get(instances.indexOf(dialog))).isSameAs(view);
        assertThat(injectors.get(instances.indexOf(call(dialog, "commands")))).isSameAs(view);
    }

    @Test
    void creatingTheProductWithNoHostFails() throws Exception {
        Injector window =
                root.scopedChildBuilder(annotation("example.WindowScoped")).build();

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> create(window, "nowhere"))
                .havingCause()
                .isInstanceOf(ScopeResolutionException.class)
                .withMessage("Cannot create Key[example.RenameDialog] with example.RenameDialogFactory.create(...):"
                        + " it is @ViewScoped, and no injector in this chain hosts @ViewScoped. Call it through an"
                        + " injector created with scopedChildBuilder(ViewScoped.class), or one of its descendants.");
    }

    /** {@return a view host below a window host, as swing-app opens a dialog} */
    private Injector dialogView() throws ClassNotFoundException {
        Injector window =
                root.scopedChildBuilder(annotation("example.WindowScoped")).build();
        return window.scopedChildBuilder(annotation("example.ViewScoped")).build();
    }

    private Object create(Injector injector, String current) throws Exception {
        Object factory = injector.getInstance(type("example.RenameDialogFactory"));
        return factory.getClass().getMethod("create", String.class).invoke(factory, current);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    private Class<? extends Annotation> annotation(String name) throws ClassNotFoundException {
        return loader.loadClass(name).asSubclass(Annotation.class);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static JavaFileObject scope(String name) {
        String simpleName = name.substring(name.lastIndexOf('.') + 1);
        return TestCompiler.source(name, """
                package example;

                import be.imgn.inject.ScopingAnnotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @ScopingAnnotation
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface %s {}
                """.formatted(simpleName));
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
