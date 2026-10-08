package be.imgn.inject.processor;

import static be.imgn.inject.processor.CompilationAssert.assertThat;
import static be.imgn.inject.processor.TestCompiler.javac;

import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class ConstructorInjectionTest {

    @Test
    void generatesFactoryForSingleParameterConstructor() {
        JavaFileObject greeter = TestCompiler.source("example.Greeter", """
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
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Greeter$$Factory");
        source.contains("class $Greeter$$Factory extends Factory<Greeter>");
        source.contains("super(Greeter.class)");
        source.contains("new Parameter(\"name\", String.class,");
        source.contains("AnnotationMetadata.empty()");
        source.contains("return new Greeter(getInstanceForConstructor(0, context))");
    }

    @Test
    void generatesFactoryForTwoParameterConstructorInOrder() {
        JavaFileObject pair = TestCompiler.source("example.Pair", """
                package example;

                import be.imgn.inject.Inject;

                public class Pair {
                    @Inject
                    public Pair(String first, Integer second) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(pair);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Pair$$Factory");
        source.contains("new Parameter(\"first\", String.class,");
        source.contains("new Parameter(\"second\", Integer.class,");
        source.contains("getInstanceForConstructor(0, context)");
        source.contains("getInstanceForConstructor(1, context)");
    }
}
