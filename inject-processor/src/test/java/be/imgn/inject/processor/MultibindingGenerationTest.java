package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class MultibindingGenerationTest {

    private static final JavaFileObject HANDLER = TestCompiler.source("example.Handler", """
            package example;

            public interface Handler {}
            """);

    @Test
    void intoSetGeneratesContributionAndNoExposedKeys() {
        JavaFileObject bean = TestCompiler.source("example.AuthHandler", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                public class AuthHandler implements Handler {
                    @Inject
                    public AuthHandler() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$AuthHandler$$Factory");
        source.contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
        source.contains("return List.of();");
    }

    @Test
    void intoMapGeneratesContributionWithKey() {
        JavaFileObject bean = TestCompiler.source("example.AuthHandler", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "auth")
                public class AuthHandler implements Handler {
                    @Inject
                    public AuthHandler() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$AuthHandler$$Factory")
                .contains("new Contribution(Key.of(Types.mapOf(String.class, Handler.class)), \"auth\")");
    }

    @Test
    void markerOnProvidesMethodWorks() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @IntoSet(Handler.class)
                    public static Handler fallback() {
                        return new Handler() {};
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$$fallback$$Factory")
                .contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
    }

    @Test
    void bothMarkersIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Both", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                @IntoMap(value = Handler.class, key = "x")
                public class Both implements Handler {
                    @Inject
                    public Both() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("one collection marker");
    }

    @Test
    void markerWithExposeAsIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                @ExposeAs(Handler.class)
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@ExposeAs");
    }

    @Test
    void markerWithQualifierIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Named;

                @IntoSet(Handler.class)
                @Named("x")
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("unqualified");
    }

    @Test
    void nonAssignableElementTypeIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Runnable.class)
                public class Bad {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assignable");
    }

    @Test
    void blankMapKeyIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = " ")
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("blank");
    }

    @Test
    void markerOnNonProvidesMethodIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.IntoSet;

                public class Bad {
                    @IntoSet(Handler.class)
                    public Handler notProvides() {
                        return new Handler() {};
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Provides");
    }

    @Test
    void duplicateInCompilationMapKeysIsAnError() {
        JavaFileObject a = TestCompiler.source("example.A", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "same")
                public class A implements Handler {
                    @Inject
                    public A() {}
                }
                """);
        JavaFileObject b = TestCompiler.source("example.B", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "same")
                public class B implements Handler {
                    @Inject
                    public B() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, a, b);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("same");
    }
}
