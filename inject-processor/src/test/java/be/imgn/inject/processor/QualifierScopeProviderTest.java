package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class QualifierScopeProviderTest {

    @Test
    void generatesQualifierForNamedParameter() {
        JavaFileObject consumer = TestCompiler.source("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Named;

                public class Consumer {
                    @Inject
                    public Consumer(@Named("main") String db) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(consumer);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Consumer$$Factory");
        source.contains("Names.named(\"main\")");
    }

    @Test
    void generatesScopeOverrideForSingletonBean() {
        JavaFileObject service = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Service {
                    @Inject
                    public Service() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contains("return Optional.of(Singleton.class)");
    }

    @Test
    void generatesProviderInjectionForProviderParameter() {
        JavaFileObject lazy = TestCompiler.source("example.Lazy", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Lazy {
                    @Inject
                    public Lazy(Provider<String> provider) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(lazy);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Lazy$$Factory");
        source.contains("new Parameter(\"provider\", String.class,");
        source.contains("return new Lazy(this.<String>getProviderForConstructor(0, String.class, context))");
    }

    @Test
    void generatesExposedKeysForQualifiedBean() {
        JavaFileObject tag = TestCompiler.source("example.Tag", """
                package example;

                import be.imgn.inject.QualifyingAnnotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;

                @QualifyingAnnotation
                @Target(ElementType.TYPE)
                public @interface Tag {
                    String value();
                }
                """);
        JavaFileObject widget = TestCompiler.source("example.Widget", """
                package example;

                import be.imgn.inject.Inject;

                @Tag("primary")
                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(tag, widget);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Widget$$Factory");
        source.contains("Key.of(Widget.class,");
        source.contains("$example$Tag$$Impl(\"primary\")");
    }

    @Test
    void rendersIntValuedCustomQualifier() {
        JavaFileObject priority = TestCompiler.source("example.Priority", """
                package example;

                import be.imgn.inject.QualifyingAnnotation;

                @QualifyingAnnotation
                public @interface Priority {
                    int value();
                }
                """);
        JavaFileObject task = TestCompiler.source("example.Task", """
                package example;

                import be.imgn.inject.Inject;

                public class Task {
                    @Inject
                    public Task(@Priority(5) String p) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(priority, task);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Task$$Factory");
        source.contains("$example$Priority$$Impl(5)");
        assertThat(compilation).generatedSourceFile("example.$example$Priority$$Impl");
    }
}
