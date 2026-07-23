package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class DefaultBindingProcessorTest {

    @Test
    void generatesIsDefaultOverrideForDefaultProvidesMethod() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String()
                .contains("protected boolean isDefault()");
    }

    @Test
    void ordinaryProvidesMethodGetsNoIsDefaultOverride() {
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
        assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String()
                .doesNotContain("isDefault");
    }

    @Test
    void defaultWithoutProvidesIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;

                public class Config {
                    @Default
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default requires @Provides.");
    }

    @Test
    void defaultWithIntoSetIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    @IntoSet(String.class)
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot join a collection contribution");
    }

    @Test
    void defaultWithIntoMapIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.IntoMap;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    @IntoMap(value = String.class, key = "k")
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot join a collection contribution");
    }
}
