package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class InterceptedProxyGenerationTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    private static final JavaFileObject AUDITED = JavaFileObjects.forSourceString("example.Audited", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Audited {}
            """);

    @Test
    void generatesAProxyThatChainsToSuper() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;

                public class Orders {
                    @Inject
                    public Orders(String region) {}

                    @Timed("place")
                    public String place(String cart) {
                        return cart;
                    }
                }
                """);
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String region() {
                        return "eu";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean, config);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("final class $Orders$$Intercepted extends Orders");
        proxy.contains("InterceptedMethod.of(Orders.class, \"place\"");
        proxy.contains("AnnotationValue.of(Timed.class");
        proxy.contains("super.place((String) $arguments[0])");
        proxy.contains("$Orders$$Intercepted(Context $context, String region)");
        proxy.contains("super(region)");
        proxy.contains("bind($context)");
        proxy.contains("catch (RuntimeException | Error");
        proxy.contains("InterceptionException");

        assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Factory")
                .contentsAsUtf8String()
                .contains("new $Orders$$Intercepted(context, getInstanceForConstructor(0, context))");
    }

    @Test
    void aConstructorParameterNamedContextDoesNotCollideWithTheProxysOwnParameter() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;

                public class Orders {
                    @Inject
                    public Orders(String context) {}

                    @Timed
                    public void place() {}
                }
                """);
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String context() {
                        return "eu";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean, config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Intercepted")
                .contentsAsUtf8String()
                .contains("$Orders$$Intercepted(Context $context, String context)");
    }

    @Test
    void classLevelAnnotationWrapsMethodLevelOnesInSourceOrder() throws IOException {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;

                @Audited
                public class Orders {
                    @Inject
                    public Orders() {}

                    @Timed
                    public void place() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, AUDITED, bean);

        assertThat(compilation).succeeded();
        String source = compilation
                .generatedSourceFile("example.$Orders$$Intercepted")
                .orElseThrow()
                .getCharContent(true)
                .toString();
        Assertions.assertThat(source.indexOf("List.of(AnnotationValue.of(Audited.class"))
                .isNotNegative()
                .isLessThan(source.indexOf("AnnotationValue.of(Timed.class"));
    }

    @Test
    void handlesVoidPrimitiveVarargsAndCheckedExceptions() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Mixed", """
                package example;

                import be.imgn.inject.Inject;
                import java.io.IOException;

                @Timed
                public class Mixed {
                    @Inject
                    public Mixed() {}

                    public void nothing() {}

                    public int count(int base) {
                        return base;
                    }

                    public String join(String... parts) {
                        return String.join("", parts);
                    }

                    public String read() throws IOException {
                        return "read";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Mixed$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("super.nothing()");
        proxy.contains("return (Integer)");
        proxy.contains("the primitive return type int");
        proxy.contains("String join(String... parts)");
        proxy.contains("catch (IOException");
    }

    @Test
    void catchesOnlyTheMostGeneralOfTwoRelatedCheckedExceptions() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Reader", """
                package example;

                import be.imgn.inject.Inject;
                import java.io.FileNotFoundException;
                import java.io.IOException;

                public class Reader {
                    @Inject
                    public Reader() {}

                    @Timed
                    public String read() throws IOException, FileNotFoundException {
                        return "read";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Reader$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("catch (IOException");
        proxy.doesNotContain("catch (FileNotFoundException");
    }

    @Test
    void rethrowsInsteadOfWrappingWhenTheMethodDeclaresThrowable() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Risky", """
                package example;

                import be.imgn.inject.Inject;

                public class Risky {
                    @Inject
                    public Risky() {}

                    @Timed
                    public String risky() throws Throwable {
                        return "risky";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Risky$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("public String risky() throws Throwable");
        proxy.doesNotContain("An interceptor threw an undeclared exception");
    }

    @Test
    void erasesTypeVariablesAndDeclaresAGenericMethodsJoinpointLocally() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Generic", """
                package example;

                import be.imgn.inject.Inject;
                import java.util.List;

                @Timed
                public class Generic {
                    @Inject
                    public Generic() {}

                    public <T> T identity(T value) {
                        return value;
                    }

                    public <T extends Number> List<T> boxed(List<T> values) {
                        return values;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Generic$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("InterceptedMethod.of(Generic.class, \"identity\", Object.class, List.of(Object.class)");
        proxy.contains("InterceptedMethod.of(Generic.class, \"boxed\", List.class, List.of(List.class)");
        proxy.contains("Joinpoint $joinpoint = $arguments -> super.identity((T) $arguments[0])");
        proxy.doesNotContain("private final Joinpoint identity");
    }

    @Test
    void mirrorsTheInterceptedMethodVisibility() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Visible", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Visible {
                    @Inject
                    public Visible() {}

                    protected String guarded() {
                        return "guarded";
                    }

                    String local() {
                        return "local";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Visible$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("protected String guarded()");
        proxy.contains("String local()");
        proxy.doesNotContain("public String local()");
    }

    @Test
    void keepsTheLocalJoinpointsParameterFromShadowingAGenericMethodParameterNamedArguments() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Picker", """
                package example;

                import be.imgn.inject.Inject;
                import java.util.List;

                public class Picker {
                    @Inject
                    public Picker() {}

                    @Timed
                    public <T> T pick(List<T> arguments) {
                        return arguments.get(0);
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
    }

    @Test
    void keepsTheHoistedJoinpointFieldsParameterFromShadowingAMethodParameterNamedArguments() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Feeder", """
                package example;

                import be.imgn.inject.Inject;

                public class Feeder {
                    @Inject
                    public Feeder() {}

                    @Timed
                    public String feed(String arguments) {
                        return arguments;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Feeder$$Intercepted")
                .contentsAsUtf8String()
                .contains("$arguments -> super.feed((String) $arguments[0])");
    }

    @Test
    void suppressesUncheckedForAGenericArrayParameter() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Shapes", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Shapes {
                    @Inject
                    public Shapes() {}

                    public <T> void genericVarargs(T[] values) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Shapes$$Intercepted")
                .contentsAsUtf8String()
                .contains("@SuppressWarnings(\"unchecked\")");
    }

    @Test
    void classLevelSweepInterceptsAnOverloadedEqualsButSkipsTheRealOne() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Money", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Money {
                    @Inject
                    public Money() {}

                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Money;
                    }

                    @Override
                    public int hashCode() {
                        return 0;
                    }

                    public boolean equals(String currency) {
                        return true;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Money$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("equals(String currency)");
        proxy.doesNotContain("equals(Object other)");
    }

    @Test
    void methodLevelAnnotationOnAnOverloadedEqualsIsAccepted() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Money", """
                package example;

                import be.imgn.inject.Inject;

                public class Money {
                    @Inject
                    public Money() {}

                    @Timed
                    public boolean equals(String currency) {
                        return true;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
    }

    @Test
    void declaresThrowableWithATypeAnnotationStillSuppressesWrapping() {
        JavaFileObject marker = JavaFileObjects.forSourceString("example.Marked", """
                package example;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;

                @Target(ElementType.TYPE_USE)
                public @interface Marked {}
                """);
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Risky", """
                package example;

                import be.imgn.inject.Inject;

                public class Risky {
                    @Inject
                    public Risky() {}

                    @Timed
                    public String risky() throws @Marked Throwable {
                        return "risky";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, marker, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Risky$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("public String risky() throws Throwable");
        proxy.doesNotContain("An interceptor threw an undeclared exception");
    }
}
