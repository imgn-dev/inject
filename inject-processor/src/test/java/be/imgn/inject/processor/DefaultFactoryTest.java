package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultFactoryTest {

    private static final JavaFileObject CONFIG = JavaFileObjects.forSourceString("example.Config", """
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
        JavaFileObject repo = JavaFileObjects.forSourceString("example.Repo", """
                package example;

                public class Repo {}
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("example.Service", """
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
                .contentsAsUtf8String()
                .contains("new Parameter(\"repo\", Repo.class,");
        assertThat(compilation)
                .generatedSourceFile("example.$Repo$$Factory")
                .contentsAsUtf8String()
                .contains("return new Repo()");
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
        JavaFileObject store = JavaFileObjects.forSourceString("example.Store", """
                package example;

                public interface Store {}
                """);
        JavaFileObject base = JavaFileObjects.forSourceString("example.Base", """
                package example;

                public abstract class Base {}
                """);
        JavaFileObject consumer = JavaFileObjects.forSourceString("example.Consumer", """
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

    private static JavaFileObject consumerOf(String type) {
        return JavaFileObjects.forSourceString("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;

                public class Consumer {
                    @Inject
                    public Consumer(%s dependency) {}
                }
                """.formatted(type));
    }

    private static JavaFileObject configModule(String annotations) {
        return JavaFileObjects.forSourceString("example.ConfigModule", """
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
