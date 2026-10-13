package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class AssistedFactoryValidationTest {

    private static final JavaFileObject REPORT = TestCompiler.source("example.Report", """
            package example;

            import be.imgn.inject.Assisted;
            import be.imgn.inject.Inject;

            public class Report {
                @Inject
                public Report(String db, @Assisted String id, @Assisted("footer") String footer) {}
            }
            """);

    @Test
    void exactReturnFactoryCompiles() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id, @Assisted("footer") String footer);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).succeeded();
    }

    @Test
    void supertypeReturnViaValueCompiles() {
        JavaFileObject doc = TestCompiler.source("example.Doc", """
                package example;

                public interface Doc {}
                """);
        JavaFileObject report = TestCompiler.source("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Report implements Doc {
                    @Inject
                    public Report(String db, @Assisted String id, @Assisted("footer") String footer) {}
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory(Report.class)
                    Doc create(String id, @Assisted("footer") String footer);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(doc, report, factory);

        assertThat(compilation).succeeded();
    }

    @Test
    void parameterMismatchIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("does not match");
    }

    @Test
    void tagMismatchIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id, @Assisted("header") String header);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("does not match");
    }

    @Test
    void returnNotAssignableIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory(Report.class)
                    String create(String id, @Assisted("footer") String footer);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("not assignable");
    }

    @Test
    void methodWithoutValueIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory(method = "x")
                    Report create(String id, @Assisted("footer") String footer);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("requires the declaring class");
    }

    @Test
    void valueWithoutAssistedConstructorIsAnError() {
        JavaFileObject dep = TestCompiler.source("example.Dep", """
                package example;

                import be.imgn.inject.Inject;

                public class Dep {
                    @Inject
                    public Dep() {}
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.DepFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface DepFactory {
                    @AssistedFactory(Dep.class)
                    Dep create();
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(dep, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("no assisted constructor");
    }

    @Test
    void providesLinkCompiles() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Long legacy(String db, @Assisted int id) {
                        return (long) id;
                    }
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.ConfigFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ConfigFactory {
                    @AssistedFactory(value = Config.class, method = "legacy")
                    Long create(int id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config, factory);

        assertThat(compilation).succeeded();
    }

    @Test
    void providesLinkUnknownMethodIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Long legacy(String db, @Assisted int id) {
                        return (long) id;
                    }
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.ConfigFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ConfigFactory {
                    @AssistedFactory(value = Config.class, method = "nope")
                    Long create(int id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("no assisted");
    }

    @Test
    void abstractNonFactoryMethodIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id, @Assisted("footer") String footer);

                    Report other();
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be implemented");
    }

    @Test
    void assistedFactoryOnClassMethodIsAnError() {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public class ReportFactory {
                    @AssistedFactory
                    public Report create(String id, @Assisted("footer") String footer) {
                        return null;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("interface method");
    }

    @Test
    void overloadedAssistedProvidesLinkIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Overloaded", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class Overloaded {
                    @Provides
                    public static Long legacy(@Assisted int id) {
                        return (long) id;
                    }

                    @Provides
                    public static Long legacy(@Assisted int id, @Assisted("tag") String tag) {
                        return (long) id;
                    }
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.LegacyFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface LegacyFactory {
                    @AssistedFactory(value = Overloaded.class, method = "legacy")
                    Long create(int id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config, factory);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("ambiguous");
    }
}
