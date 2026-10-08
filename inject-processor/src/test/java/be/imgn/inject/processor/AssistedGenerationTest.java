package be.imgn.inject.processor;

import static be.imgn.inject.processor.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Optional;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class AssistedGenerationTest {

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
    void generatesImplAndFactory() throws Exception {
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

        CompilationAssert.assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("example.$ReportFactory$$Impl"))
                .isPresent();
        assertThat(compilation.generatedSourceFile("example.$ReportFactory$$Factory"))
                .isPresent();
        assertThat(contents(compilation, "example.$ReportFactory$$Impl"))
                .contains("implements ReportFactory")
                .contains("new Report(")
                .contains("context.getProvider(");
        assertThat(contents(compilation, "example.$ReportFactory$$Factory")).contains("super(ReportFactory.class)");
    }

    @Test
    void implPassesAssistedArgumentsInRecipeOrder() throws Exception {
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(@Assisted("footer") String footer, String id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(REPORT, factory);

        CompilationAssert.assertThat(compilation).succeeded();
        assertThat(contents(compilation, "example.$ReportFactory$$Impl"))
                .contains("new Report(")
                .contains(", id, footer)");
    }

    @Test
    void memberInjectionRunsInsideCreate() throws IOException {
        JavaFileObject report = TestCompiler.source("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Report {
                    @Inject
                    String tag;

                    @Inject
                    public Report(@Assisted String id) {}

                    @Inject
                    void configure(String note) {}
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(report, factory);

        CompilationAssert.assertThat(compilation).succeeded();
        assertThat(contents(compilation, "example.$ReportFactory$$Impl"))
                .contains("Report instance = new Report(")
                .contains("instance.tag = ")
                .contains("instance.configure(")
                .contains("return instance");

        String source = compilation
                .generatedSourceFile("example.$ReportFactory$$Impl")
                .orElseThrow()
                .getCharContent(false)
                .toString();
        assertThat(source.indexOf("instance.configure(")).isLessThan(source.indexOf("instance.tag ="));
    }

    @Test
    void assistedParameterTypesAreNotContainerResolved() throws Exception {
        JavaFileObject report = TestCompiler.source("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Context;

                public class Report {
                    @Inject
                    public Report(String db, @Assisted Context ctx) {}
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;
                import be.imgn.inject.context.Context;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(Context ctx);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(report, factory);

        CompilationAssert.assertThat(compilation).succeeded();
    }

    @Test
    void providesRecipeInvokesHost() throws Exception {
        JavaFileObject staticConfig = TestCompiler.source("example.Config", """
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
        JavaFileObject staticFactory = TestCompiler.source("example.ConfigFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ConfigFactory {
                    @AssistedFactory(value = Config.class, method = "legacy")
                    Long create(int id);
                }
                """);

        Compilation staticCompilation =
                javac().withProcessors(new InjectProcessor()).compile(staticConfig, staticFactory);

        CompilationAssert.assertThat(staticCompilation).succeeded();
        assertThat(contents(staticCompilation, "example.$ConfigFactory$$Impl")).contains("Config.legacy(");

        JavaFileObject instanceConfig = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Provides;

                public class Config {
                    @Inject
                    public Config() {}

                    @Provides
                    public Long legacy(@Assisted int id) {
                        return (long) id;
                    }
                }
                """);
        JavaFileObject instanceFactory = TestCompiler.source("example.ConfigFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ConfigFactory {
                    @AssistedFactory(value = Config.class, method = "legacy")
                    Long create(int id);
                }
                """);

        Compilation instanceCompilation =
                javac().withProcessors(new InjectProcessor()).compile(instanceConfig, instanceFactory);

        CompilationAssert.assertThat(instanceCompilation).succeeded();
        assertThat(contents(instanceCompilation, "example.$ConfigFactory$$Impl"))
                .contains("context.getProvider(Key.of(Config.class)");
    }

    @Test
    void serviceFileListsTheGeneratedFactory() throws Exception {
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

        CompilationAssert.assertThat(compilation).succeeded();
        Optional<JavaFileObject> serviceFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.inject.context.Factory");
        assertThat(serviceFile).isPresent();
        assertThat(serviceFile.orElseThrow().getCharContent(true).toString())
                .contains("example.$ReportFactory$$Factory");
    }

    @Test
    void factoryParametersCannotShadowTheContextOrTheGeneratedLocals() throws Exception {
        JavaFileObject dep = TestCompiler.source("example.Dep", """
                package example;

                public class Dep {}
                """);
        JavaFileObject widget = TestCompiler.source("example.Widget", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;

                public class Widget {
                    @Inject
                    public Widget(Dep dep, @Assisted Injector context, @Assisted("label") String instance) {}
                }
                """);
        JavaFileObject factory = TestCompiler.source("example.WidgetFactory", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.AssistedFactory;
                import be.imgn.inject.Injector;

                public interface WidgetFactory {
                    @AssistedFactory
                    Widget create(Injector context, @Assisted("label") String instance);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(dep, widget, factory);

        CompilationAssert.assertThat(compilation).succeeded();
        assertThat(contents(compilation, "example.$WidgetFactory$$Impl"))
                .as("container dependencies resolve through the impl's own context, never a caller-supplied one")
                .contains("this.context.getProvider(Key.of(Dep.class)).get()")
                .doesNotContainPattern("[^.]context\\.getProvider")
                .contains("Widget instance_ = new Widget(")
                .contains("return instance_;");
    }

    private static String contents(Compilation compilation, String qualifiedName) throws IOException {
        return compilation
                .generatedSourceFile(qualifiedName)
                .orElseThrow()
                .getCharContent(true)
                .toString();
    }
}
