package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import be.imgn.inject.Injector;
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

class EagerEndToEndTest {
    private static final JavaFileObject EVENTS = TestCompiler.source("example.Events", """
            package example;

            import java.util.ArrayList;
            import java.util.List;

            public final class Events {
                public static final List<String> LOG = new ArrayList<>();

                private Events() {}
            }
            """);

    private static final JavaFileObject STORE = TestCompiler.source("example.Store", """
            package example;

            import be.imgn.inject.Inject;
            import be.imgn.inject.PreDestroy;
            import be.imgn.inject.Singleton;

            @Singleton
            public class Store {
                @Inject
                public Store() {
                    Events.LOG.add("store built");
                }

                @PreDestroy
                void close() {
                    Events.LOG.add("store destroyed");
                }
            }
            """);

    private static final JavaFileObject RECORDER = TestCompiler.source("example.Recorder", """
            package example;

            import be.imgn.inject.ConstructionListener;
            import be.imgn.inject.Inject;
            import be.imgn.inject.Injector;
            import be.imgn.inject.IntoSet;
            import be.imgn.inject.Singleton;

            @Singleton
            @IntoSet(ConstructionListener.class)
            public class Recorder implements ConstructionListener {
                @Inject
                public Recorder() {}

                @Override
                public void constructed(Object instance, Injector injector) {
                    Events.LOG.add("reported " + instance.getClass().getSimpleName());
                }
            }
            """);

    @Test
    void eagerSingletonsAreBuiltAndReportedWhenTheInjectorIsCreated(@TempDir Path classes) throws Exception {
        JavaFileObject audit = TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Audit {
                    public Audit() {
                        Events.LOG.add("audit built");
                    }
                }
                """);
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Config {
                    @Provides
                    @Eager
                    @Singleton
                    public static StringBuilder banner() {
                        Events.LOG.add("banner built");
                        return new StringBuilder("banner");
                    }
                }
                """);

        withClasses(classes, List.of(EVENTS, RECORDER, audit, config, STORE), loader -> {
            Injector.create();

            Assertions.assertThat(log(loader))
                    .containsExactlyInAnyOrder(
                            "audit built", "reported Audit", "banner built", "reported StringBuilder");
        });
    }

    @Test
    void aFailingEagerSingletonFailsTheCreationAndDestroysWhatWasBuilt(@TempDir Path classes) throws Exception {
        JavaFileObject failing = TestCompiler.source("example.Failing", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Failing {
                    @Inject
                    public Failing(Store store) {
                        throw new IllegalStateException("eager failed");
                    }
                }
                """);

        withClasses(classes, List.of(EVENTS, STORE, failing), loader -> {
            assertThatIllegalStateException().isThrownBy(Injector::create).withMessage("eager failed");

            Assertions.assertThat(log(loader)).containsExactly("store built", "store destroyed");
        });
    }

    @Test
    void aFailingListenerFailsTheCreationAndDestroysWhatWasBuilt(@TempDir Path classes) throws Exception {
        JavaFileObject listener = TestCompiler.source("example.Rejecting", """
                package example;

                import be.imgn.inject.ConstructionListener;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;
                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Singleton;

                @Singleton
                @IntoSet(ConstructionListener.class)
                public class Rejecting implements ConstructionListener {
                    @Inject
                    public Rejecting(Store store) {
                        throw new IllegalStateException("listener failed");
                    }

                    @Override
                    public void constructed(Object instance, Injector injector) {}
                }
                """);

        withClasses(classes, List.of(EVENTS, STORE, listener), loader -> {
            assertThatIllegalStateException().isThrownBy(Injector::create).withMessage("listener failed");

            Assertions.assertThat(log(loader)).containsExactly("store built", "store destroyed");
        });
    }

    /** Compiles {@code sources} into {@code classes}, then runs {@code action} with them as the context loader. */
    private void withClasses(Path classes, List<JavaFileObject> sources, LoaderAction action) throws Exception {
        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(sources);
        assertThat(compilation).succeeded();
        writeClasses(compilation, classes);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                action.run(loader);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    /** {@return the compiled {@code example.Events.LOG}} */
    private static List<Object> log(ClassLoader loader) throws Exception {
        return List.copyOf(
                (List<?>) loader.loadClass("example.Events").getField("LOG").get(null));
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

    @FunctionalInterface
    private interface LoaderAction {
        void run(ClassLoader loader) throws Exception;
    }
}
