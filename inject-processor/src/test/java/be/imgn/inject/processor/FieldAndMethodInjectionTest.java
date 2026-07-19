package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class FieldAndMethodInjectionTest {

    @Test
    void generatesFieldInjection() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Inject;

                public class Config {
                    @Inject String greeting;
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Config$$Factory")
                .contentsAsUtf8String();
        source.contains("new FieldInjectionPoint(this, \"greeting\"");
        source.contains("instance.greeting = getInstanceForField(0, context)");
    }

    @Test
    void generatesMethodInjection() {
        JavaFileObject service = JavaFileObjects.forSourceString("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    @Inject void setClock(String clock) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String();
        source.contains("new MethodInjectionPoint(this, \"setClock\"");
        source.contains("new Parameter(\"clock\", String.class,");
        source.contains("instance.setClock(getInstanceForMethod(0, 0, context))");
    }

    @Test
    void generatesConstructorFieldAndMethodTogether() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bean", """
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
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Bean$$Factory")
                .contentsAsUtf8String();
        source.contains("super(Bean.class)");
        source.contains("return new Bean(getInstanceForConstructor(0, context))");
        source.contains("instance.counter = getInstanceForField(0, context)");
        source.contains("instance.init(getInstanceForMethod(0, 0, context))");
    }
}
