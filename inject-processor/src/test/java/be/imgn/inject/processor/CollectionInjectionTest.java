package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class CollectionInjectionTest {

    @Test
    void rendersSetDependencyWithTypesSetOf() {
        JavaFileObject router = TestCompiler.source("example.Router", """
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
        assertThat(compilation).generatedSourceFile("example.$Router$$Factory").contains("Types.setOf(String.class)");
    }

    @Test
    void rendersMapDependencyWithTypesMapOf() {
        JavaFileObject router = TestCompiler.source("example.Router", """
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
                .contains("Types.mapOf(String.class, Integer.class)");
    }

    @Test
    void providerOfSetComposes() {
        JavaFileObject lazy = TestCompiler.source("example.Lazy", """
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
        assertThat(compilation).generatedSourceFile("example.$Lazy$$Factory").contains("Types.setOf(String.class)");
        assertThat(compilation)
                .generatedSourceFile("example.$Lazy$$Factory")
                .contains("this.<Set<String>>getProviderForConstructor(0, Set.class, context)");
    }

    @Test
    void rawSetIsAnError() {
        JavaFileObject bad = TestCompiler.source("example.Bad", """
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
        JavaFileObject bad = TestCompiler.source("example.Bad", """
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
    void providesReturningParameterizedSetExposesTheCollectionKey() {
        JavaFileObject handler = TestCompiler.source("example.Handler", """
                package example;

                public interface Handler {}
                """);
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Set<Handler> handlers() {
                        return Set.of();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(handler, config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$handlers$$Factory")
                .contains("Key.of(Types.setOf(Handler.class))");
    }

    @Test
    void providesReturningRawSetCompilesWithoutTheCollectionKeyOverride() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @SuppressWarnings("rawtypes")
                    public static Set handlers() {
                        return Set.of();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$handlers$$Factory")
                .doesNotContain("exposedKeys");
    }

    @Test
    void setOfProviderIsAnError() {
        JavaFileObject bad = TestCompiler.source("example.Bad", """
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
