package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class EagerProcessorTest {

    @Test
    void generatesIsEagerForAnEagerSingleton() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Audit {
                    @Inject
                    public Audit() {}
                }
                """));

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Audit$$Factory").contains("protected boolean isEager()");
    }

    @Test
    void anEagerClassNeedsNoInjectMember() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Audit {}
                """));

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Audit$$Factory").contains("protected boolean isEager()");
    }

    @Test
    void anInheritedSingletonCountsForEager() {
        JavaFileObject base = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Singleton;

                @Singleton
                public abstract class Service {}
                """);
        JavaFileObject audit = TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;

                @Eager
                public class Audit extends Service {}
                """);

        Compilation compilation = compile(base, audit);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Audit$$Factory").contains("protected boolean isEager()");
    }

    @Test
    void generatesIsEagerForAnEagerProvidesMethod() {
        Compilation compilation = compile(TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Config {
                    @Provides
                    @Eager
                    @Singleton
                    public static String name() {
                        return "eager";
                    }
                }
                """));

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$$name$$Factory")
                .contains("protected boolean isEager()");
    }

    @Test
    void aSingletonThatIsNotEagerGetsNoIsEager() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Audit {
                    @Inject
                    public Audit() {}
                }
                """));

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Audit$$Factory").doesNotContain("isEager");
    }

    @Test
    void eagerAndDefaultCombine() {
        Compilation compilation = compile(TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.Eager;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Config {
                    @Provides
                    @Default
                    @Eager
                    @Singleton
                    public static String name() {
                        return "fallback";
                    }
                }
                """));

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$$name$$Factory")
                .contains("protected boolean isDefault()")
                .contains("protected boolean isEager()");
    }

    @Test
    void eagerOnAMethodWithoutProvidesIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Eager;

                public class Config {
                    @Eager
                    public static String name() {
                        return "eager";
                    }
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager on a method requires @Provides.");
    }

    @Test
    void eagerOnAnInterfaceIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public interface Audit {}
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager belongs on a concrete class or a @Provides method.");
    }

    @Test
    void eagerOnAnAbstractClassIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public abstract class Audit {}
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager belongs on a concrete class or a @Provides method.");
    }

    @Test
    void anUnscopedEagerBeanIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Inject;

                @Eager
                public class Audit {
                    @Inject
                    public Audit() {}
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager requires @Singleton: example.Audit is unscoped.");
    }

    @Test
    void anEagerBeanOfAnotherScopeIsAnError() {
        JavaFileObject scope = TestCompiler.source("example.WindowScoped", """
                package example;

                import be.imgn.inject.ScopingAnnotation;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @ScopingAnnotation
                @Retention(RetentionPolicy.RUNTIME)
                public @interface WindowScoped {}
                """);
        JavaFileObject audit = TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;

                @Eager
                @WindowScoped
                public class Audit {}
                """);

        Compilation compilation = compile(scope, audit);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager requires @Singleton: example.Audit is @WindowScoped.");
    }

    @Test
    void anEagerProvidesMethodThatIsNotSingletonIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Eager
                    public static String name() {
                        return "eager";
                    }
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager requires @Singleton on @Provides name().");
    }

    @Test
    void anEagerAssistedProductIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Eager;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Report {
                    @Inject
                    public Report(@Assisted String id) {}
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("@Eager cannot mark an assisted product: only its @AssistedFactory builds it.");
    }

    @Test
    void anEagerAssistedRecipeIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Eager;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Config {
                    @Provides
                    @Eager
                    @Singleton
                    public static StringBuilder report(@Assisted String id) {
                        return new StringBuilder(id);
                    }
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Eager cannot mark an assisted recipe.");
    }

    @Test
    void anEagerBeanWithoutAUsableConstructorIsAnError() {
        Compilation compilation = compile(TestCompiler.source("example.Audit", """
                package example;

                import be.imgn.inject.Eager;
                import be.imgn.inject.Singleton;

                @Eager
                @Singleton
                public class Audit {
                    public Audit(String name) {}
                }
                """));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("example.Audit is @Eager but has no @Inject constructor and no accessible"
                        + " no-argument constructor.");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return javac().withProcessors(new InjectProcessor()).compile(sources);
    }
}
