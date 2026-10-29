package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DefaultBindingProcessorTest {

    @Test
    void generatesIsDefaultOverrideForDefaultProvidesMethod() {
        JavaFileObject config = TestCompiler.source("example.Config", """
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
                .generatedSourceFile("example.$Config$$provideName$$Factory")
                .contains("protected boolean isDefault()");
    }

    @Test
    void ordinaryProvidesMethodGetsNoIsDefaultOverride() {
        JavaFileObject config = TestCompiler.source("example.Config", """
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
                .generatedSourceFile("example.$Config$$provideName$$Factory")
                .doesNotContain("isDefault");
    }

    @Test
    void defaultWithoutProvidesIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Config", """
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
        JavaFileObject config = TestCompiler.source("example.Config", """
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
        JavaFileObject config = TestCompiler.source("example.Config", """
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

    @Test
    void defaultOnAnAssistedRecipeIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Default;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    public static String provideName(@Assisted String seed) {
                        return seed;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot mark an assisted recipe.");
    }
}
