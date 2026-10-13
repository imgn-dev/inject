package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class PluginSpiTest {

    @Test
    void pluginInitializesInjectingAnnotationField() {
        JavaFileObject bean = TestCompiler.source("example.Tagged", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Marker;

                public class Tagged {
                    @Marker
                    String tag;

                    @Inject
                    public Tagged() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Tagged$$Factory");
        source.contains("instance.tag = \"marked\"");
        source.doesNotContain("fieldInjectionPoints");
    }

    @Test
    void pluginInitializesConstructorParameter() {
        JavaFileObject bean = TestCompiler.source("example.Tagged", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Marker;

                public class Tagged {
                    @Inject
                    public Tagged(@Marker String tag) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Tagged$$Factory");
        source.contains("return new Tagged(\"marked\")");
        source.doesNotContain("getInstanceForConstructor");
    }

    @Test
    void pluginInitializesMethodParameter() {
        JavaFileObject bean = TestCompiler.source("example.Tagged", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Marker;

                public class Tagged {
                    @Inject
                    public Tagged() {}

                    @Inject
                    void configure(@Marker String tag) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Tagged$$Factory");
        source.contains("instance.configure(\"marked\")");
        source.doesNotContain("getInstanceForMethod");
    }

    @Test
    void rejectsPrivateInjectingAnnotationField() {
        JavaFileObject bean = TestCompiler.source("example.Tagged", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Marker;

                public class Tagged {
                    @Marker
                    private String tag;

                    @Inject
                    public Tagged() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not be private, static, or final");
    }

    @Test
    void errorsWhenNoPluginHandlesInjectingAnnotation() {
        JavaFileObject bean = TestCompiler.source("example.Orphan", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Unhandled;

                public class Orphan {
                    @Unhandled
                    String value;

                    @Inject
                    public Orphan() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("No plugin handles");
    }
}
