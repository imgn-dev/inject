package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class MultibindingGenerationTest {

    private static final JavaFileObject HANDLER = JavaFileObjects.forSourceString("example.Handler", """
            package example;

            public interface Handler {}
            """);

    @Test
    void intoSetGeneratesContributionAndNoExposedKeys() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.AuthHandler", """
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
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$AuthHandler$$Factory")
                .contentsAsUtf8String();
        source.contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
        source.contains("return List.of();");
    }

    @Test
    void intoMapGeneratesContributionWithKey() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.AuthHandler", """
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
                .contentsAsUtf8String()
                .contains("new Contribution(Key.of(Types.mapOf(String.class, Handler.class)), \"auth\")");
    }

    @Test
    void markerOnProvidesMethodWorks() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
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
                .generatedSourceFile("example.$Config$fallback$$Factory")
                .contentsAsUtf8String()
                .contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
    }

    @Test
    void bothMarkersIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Both", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject config = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject a = JavaFileObjects.forSourceString("example.A", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "same")
                public class A implements Handler {
                    @Inject
                    public A() {}
                }
                """);
        JavaFileObject b = JavaFileObjects.forSourceString("example.B", """
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
