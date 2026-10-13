package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultFactoryTest {

    private static final JavaFileObject CONFIG = TestCompiler.source("example.Config", """
            package example;

            public class Config {
                private final String origin;

                public Config() {
                    this("constructor");
                }

                Config(String origin) {
                    this.origin = origin;
                }

                public String origin() {
                    return origin;
                }
            }
            """);

    @Test
    void generatesDefaultFactoryForUndeclaredDependencyWithNoArgConstructor() {
        JavaFileObject repo = TestCompiler.source("example.Repo", """
                package example;

                public class Repo {}
                """);
        JavaFileObject service = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    @Inject
                    public Service(Repo repo) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(repo, service);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contains("new Parameter(\"repo\", Repo.class,");
        assertThat(compilation).generatedSourceFile("example.$Repo$$Factory").contains("return new Repo()");
    }

    @Test
    void noDefaultFactoryForATypeADefaultProvidesMethodBinds() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(CONFIG, consumerOf("Config"), configModule("@Provides\n    @Default"));

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .as("a generated Config() factory would win over the @Default @Provides binding")
                .noneMatch(file -> file.getName().contains("$Config$$Factory"));
    }

    @Test
    void noDefaultFactoryForATypeAProvidesMethodBinds() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(CONFIG, consumerOf("Config"), configModule("@Provides"));

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(file -> file.getName().contains("$Config$$Factory"));
    }

    @Test
    void noDefaultFactoryForAnInterfaceOrAbstractClass() {
        JavaFileObject store = TestCompiler.source("example.Store", """
                package example;

                public interface Store {}
                """);
        JavaFileObject base = TestCompiler.source("example.Base", """
                package example;

                public abstract class Base {}
                """);
        JavaFileObject consumer = TestCompiler.source("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;

                public class Consumer {
                    @Inject
                    public Consumer(Store store, Base base) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(store, base, consumer);

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(file -> file.getName().contains("$Store$$Factory")
                        || file.getName().contains("$Base$$Factory"));
    }

    @Test
    void noDefaultFactoryForAConcreteTypeWithoutAUsableConstructor() {
        JavaFileObject display = TestCompiler.source("example.DisplayConfig", """
                package example;

                public record DisplayConfig(int width, int height) {}
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(display, consumerOf("DisplayConfig"));

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(file -> file.getName().contains("$DisplayConfig$$Factory"));
    }

    private static JavaFileObject consumerOf(String type) {
        return TestCompiler.source("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;

                public class Consumer {
                    @Inject
                    public Consumer(%s dependency) {}
                }
                """.formatted(type));
    }

    private static JavaFileObject configModule(String annotations) {
        return TestCompiler.source("example.ConfigModule", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.Provides;

                public class ConfigModule {
                    %s
                    public static Config config() {
                        return new Config("provides");
                    }
                }
                """.formatted(annotations));
    }
}
