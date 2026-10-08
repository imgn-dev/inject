package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ConstructorInjectionTest {

    @Test
    void generatesFactoryForSingleParameterConstructor() {
        JavaFileObject greeter = JavaFileObjects.forSourceString("example.Greeter", """
                package example;

                import be.imgn.inject.Inject;

                public class Greeter {
                    private final String name;

                    @Inject
                    public Greeter(String name) {
                        this.name = name;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(greeter);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Greeter$$Factory")
                .contentsAsUtf8String();
        source.contains("class $Greeter$$Factory extends Factory<Greeter>");
        source.contains("super(Greeter.class)");
        source.contains("new Parameter(\"name\", String.class,");
        source.contains("AnnotationMetadata.empty()");
        source.contains("return new Greeter(getInstanceForConstructor(0, context))");
    }

    @Test
    void generatesFactoryForTwoParameterConstructorInOrder() {
        JavaFileObject pair = JavaFileObjects.forSourceString("example.Pair", """
                package example;

                import be.imgn.inject.Inject;

                public class Pair {
                    @Inject
                    public Pair(String first, Integer second) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(pair);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Pair$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"first\", String.class,");
        source.contains("new Parameter(\"second\", Integer.class,");
        source.contains("getInstanceForConstructor(0, context)");
        source.contains("getInstanceForConstructor(1, context)");
    }
}
