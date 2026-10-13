package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class FieldAndMethodInjectionTest {

    @Test
    void generatesFieldInjection() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Inject;

                public class Config {
                    @Inject String greeting;
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Config$$Factory");
        source.contains("new FieldInjectionPoint(this, \"greeting\"");
        source.contains("instance.greeting = this.<String>getInstanceForField(0, String.class, context)");
    }

    @Test
    void generatesMethodInjection() {
        JavaFileObject service = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    @Inject void setClock(String clock) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Service$$Factory");
        source.contains("new MethodInjectionPoint(this, \"setClock\"");
        source.contains("new Parameter(\"clock\", String.class,");
        source.contains("instance.setClock(this.<String>getInstanceForMethod(0, 0, String.class, context))");
    }

    @Test
    void generatesConstructorFieldAndMethodTogether() {
        JavaFileObject bean = TestCompiler.source("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject Integer counter;

                    @Inject
                    public Bean(String name) {}

                    @Inject void init(Long id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Bean$$Factory");
        source.contains("super(Bean.class)");
        source.contains("return new Bean(this.<String>getInstanceForConstructor(0, String.class, context))");
        source.contains("instance.counter = this.<Integer>getInstanceForField(0, Integer.class, context)");
        source.contains("instance.init(this.<Long>getInstanceForMethod(0, 0, Long.class, context))");
    }
}
