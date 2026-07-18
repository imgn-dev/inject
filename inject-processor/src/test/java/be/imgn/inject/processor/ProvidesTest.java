package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class ProvidesTest {

    @Test
    void generatesFactoryForStaticProvidesMethod() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String provideName() {
                        return "hi";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String();
        source.contains("extends Factory<String>");
        source.contains("super(String.class)");
        source.contains("return Config.provideName()");
    }

    @Test
    void generatesFactoryForInstanceProvidesMethodResolvingHostFirst() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public String provideName() {
                        return "hi";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"host\", Config.class)");
        source.contains("Config host = getInstanceForConstructor(0, context)");
        source.contains("return host.provideName()");
        // the host itself gets a default factory
        assertThat(compilation).generatedSourceFile("example.$Config$$Factory");
    }

    @Test
    void resolvesProvidesMethodParameters() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String greeting(Integer count) {
                        return "";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Config$greeting$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"count\", Integer.class)");
        source.contains("return Config.greeting(getInstanceForConstructor(0, context))");
    }

    @Test
    void appliesQualifierAndScopeFromProvidesMethod() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Named;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Config {
                    @Provides
                    @Named("db")
                    @Singleton
                    public static String url() {
                        return "";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Config$url$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(String.class,");
        source.contains("Map.of(\"value\", \"db\")");
        source.contains("return Optional.of(Singleton.class)");
    }

    @Test
    void disambiguatesOverloadedProvidesMethods() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String make() {
                        return "a";
                    }

                    @Provides
                    public static Integer make(String seed) {
                        return 1;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Config$make$$Factory");
        assertThat(compilation).generatedSourceFile("example.$Config$make$1$$Factory");
    }
}
