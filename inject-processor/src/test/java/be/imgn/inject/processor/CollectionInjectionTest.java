package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class CollectionInjectionTest {

    @Test
    void rendersSetDependencyWithTypesSetOf() {
        JavaFileObject router = JavaFileObjects.forSourceString("example.Router", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;

                public class Router {
                    @Inject
                    public Router(Set<String> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(router);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Router$$Factory")
                .contentsAsUtf8String()
                .contains("Types.setOf(String.class)");
    }

    @Test
    void rendersMapDependencyWithTypesMapOf() {
        JavaFileObject router = JavaFileObjects.forSourceString("example.Router", """
                package example;

                import java.util.Map;
                import be.imgn.inject.Inject;

                public class Router {
                    @Inject
                    public Router(Map<String, Integer> byName) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(router);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Router$$Factory")
                .contentsAsUtf8String()
                .contains("Types.mapOf(String.class, Integer.class)");
    }

    @Test
    void providerOfSetComposes() {
        JavaFileObject lazy = JavaFileObjects.forSourceString("example.Lazy", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Lazy {
                    @Inject
                    public Lazy(Provider<Set<String>> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(lazy);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Lazy$$Factory")
                .contentsAsUtf8String()
                .contains("Types.setOf(String.class)");
        assertThat(compilation)
                .generatedSourceFile("example.$Lazy$$Factory")
                .contentsAsUtf8String()
                .contains("getProviderForConstructor(0, context)");
    }

    @Test
    void rawSetIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    @SuppressWarnings("rawtypes")
                    public Bad(Set handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("raw Set");
    }

    @Test
    void nonStringMapKeyIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Map;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad(Map<Integer, String> byNumber) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("String keys");
    }

    @Test
    void setOfProviderIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Bad {
                    @Inject
                    public Bad(Set<Provider<String>> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Set<Provider");
    }
}
