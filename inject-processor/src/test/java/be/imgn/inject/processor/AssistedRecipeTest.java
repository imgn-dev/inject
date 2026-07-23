package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AssistedRecipeTest {

    private static final JavaFileObject ASSISTED_BEAN = JavaFileObjects.forSourceString("example.Report", """
            package example;

            import be.imgn.inject.Assisted;
            import be.imgn.inject.Inject;

            public class Report {
                @Inject
                public Report(String db, @Assisted String id) {}
            }
            """);

    @Test
    void assistedBeanGeneratesNoOwnFactory() {
        JavaFileObject dep = JavaFileObjects.forSourceString("example.Dep", """
                package example;

                import be.imgn.inject.Inject;

                public class Dep {
                    @Inject
                    public Dep() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, dep);

        assertThat(compilation).succeeded();
        // the assisted bean must not get a factory; the ordinary bean still does
        assertThat(compilation).generatedSourceFile("example.$Dep$$Factory");
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(f -> f.getName().contains("$Report$$Factory"));
    }

    @Test
    void injectingAnAssistedBeanDirectlyIsAnError() {
        JavaFileObject consumer = JavaFileObjects.forSourceString("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;

                public class Consumer {
                    @Inject
                    public Consumer(Report report) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, consumer);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("only available through an @AssistedFactory");
    }

    @Test
    void providesParameterReferencingAssistedBeanIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Long legacy(Report report) {
                        return 1L;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("only available through an @AssistedFactory");
    }

    @Test
    void assistedRecipeContainerParamReferencingAssistedBeanIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad(Report report, @Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("only available through an @AssistedFactory");
    }

    @Test
    void conditionalAssistedBeanIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.ConditionalOnProperty;
                import be.imgn.inject.Inject;

                @ConditionalOnProperty("x")
                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("factory interface");
    }

    @Test
    void scopedAssistedBeanIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void qualifiedOrExposedOrContributedAssistedBeanIsAnError() {
        JavaFileObject exposed = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs(CharSequence.class)
                public class Bad implements CharSequence {
                    @Inject
                    public Bad(@Assisted String id) {}

                    @Override public int length() { return 0; }
                    @Override public char charAt(int i) { return 'x'; }
                    @Override public CharSequence subSequence(int a, int b) { return this; }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(exposed);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void assistedProvidesMethodBindsNothing() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Long legacy(String db, @Assisted int id) {
                        return (long) id;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(f -> f.getName().contains("$Config$legacy$$Factory"));
    }

    @Test
    void scopedAssistedProvidesIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Bad {
                    @Provides
                    @Singleton
                    public static Long legacy(@Assisted int id) {
                        return (long) id;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void misplacedAssistedIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @Inject
                    public void configure(@Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("misplaced");
    }

    @Test
    void misplacedAssistedOnAssistedBeanMethodIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}

                    @Inject
                    public void configure(@Assisted String x) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("misplaced");
    }

    @Test
    void containerDependencyOfAssistedRecipeGetsADefaultFactory() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.SomeConfig", """
                package example;

                public class SomeConfig {
                    public SomeConfig() {}
                }
                """);
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Report {
                    @Inject
                    public Report(SomeConfig config, @Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config, bean);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$SomeConfig$$Factory");
    }

    @Test
    void hostOfInstanceAssistedProvidesGetsADefaultFactory() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.HostConfig", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class HostConfig {
                    public HostConfig() {}

                    @Provides
                    public Long legacy(@Assisted int id) {
                        return (long) id;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$HostConfig$$Factory");
    }
}
