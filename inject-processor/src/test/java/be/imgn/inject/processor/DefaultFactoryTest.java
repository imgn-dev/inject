package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DefaultFactoryTest {

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
}
