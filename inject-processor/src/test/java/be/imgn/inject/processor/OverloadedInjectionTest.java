package be.imgn.inject.processor;

import static be.imgn.inject.processor.CompilationAssert.assertThat;
import static be.imgn.inject.processor.TestCompiler.javac;

import org.junit.jupiter.api.Test;

/**
 * Generated factories call constructors and methods that may have overloads of the same arity. Each resolution call
 * names its dependency type, so javac selects the injected overload; under {@code -Xlint:all -Werror} the generated
 * code must compile without warnings. The {@code processing} category is left out: the processor claims no annotation,
 * so that other processors still see them, and javac reports that under it.
 */
class OverloadedInjectionTest {

    private static Compilation compile(String qualifiedName, String source) {
        return javac().withProcessors(new InjectProcessor())
                .withOptions("-Xlint:all,-processing", "-Werror")
                .compile(TestCompiler.source(qualifiedName, source));
    }

    @Test
    void constructorOverloadedByParameterType() {
        Compilation compilation = compile("example.Screen", """
                package example;

                import be.imgn.inject.Inject;

                public class Screen {
                    @Inject
                    public Screen(String title) {}

                    public Screen(Integer width) {}
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Screen$$Factory")
                .contains("return new Screen(this.<String>getInstanceForConstructor(0, String.class, context))");
    }

    @Test
    void methodOverloadedByParameterType() {
        Compilation compilation = compile("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    @Inject
                    void configure(String name) {}

                    void configure(Integer size) {}
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Service$$Factory")
                .contains("instance.configure(this.<String>getInstanceForMethod(0, 0, String.class, context))");
    }

    @Test
    void providesMethodOverloadedByParameterType() {
        Compilation compilation = compile("example.Labels", """
                package example;

                import be.imgn.inject.Provides;

                public class Labels {
                    @Provides
                    public static CharSequence label(String text) {
                        return text;
                    }

                    public static CharSequence label(Integer number) {
                        return number.toString();
                    }
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Labels$label$$Factory")
                .contains("return Labels.label(this.<String>getInstanceForConstructor(0, String.class, context))");
    }

    @Test
    void constructorOverloadedByTypeArgument() {
        Compilation compilation = compile("example.Registry", """
                package example;

                import be.imgn.inject.Inject;
                import java.util.Map;
                import java.util.Set;

                public class Registry {
                    @Inject
                    public Registry(Set<String> names) {}

                    public Registry(Map<String, String> names) {}
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Registry$$Factory")
                .contains("this.<Set<String>>getInstanceForConstructor(0, Set.class, context)");
    }

    @Test
    void primitiveParameterKeepsItsOverload() {
        Compilation compilation = compile("example.Sizer", """
                package example;

                import be.imgn.inject.Inject;

                public class Sizer {
                    @Inject
                    public Sizer(int width) {}

                    public Sizer(Integer width) {}
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Sizer$$Factory")
                .contains("return new Sizer((int) this.<Integer>getInstanceForConstructor(0, int.class, context))");
    }

    @Test
    void providerParameterOverloadedByProvidedType() {
        Compilation compilation = compile("example.Lazy", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Lazy {
                    @Inject
                    public Lazy(Provider<String> text) {}

                    public Lazy(Provider<Integer> number, boolean unused) {}

                    public Lazy(Runnable action) {}
                }
                """);

        assertThat(compilation)
                .succeeded()
                .hadNoWarnings()
                .generatedSourceFile("example.$Lazy$$Factory")
                .contains("return new Lazy(this.<String>getProviderForConstructor(0, String.class, context))");
    }
}
