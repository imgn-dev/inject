package be.imgn.inject.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleInfoGeneratorTest {

    private static final String FACTORY = "be.imgn.inject.context.Factory";
    private static final String SCOPE = "be.imgn.inject.context.Scope";

    @TempDir
    Path classes;

    private void compiledClass(String path) throws IOException {
        Path file = classes.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE});
    }

    private ModuleDescriptor generatedDescriptor() throws IOException {
        try (var input = Files.newInputStream(classes.resolve("module-info.class"))) {
            return ModuleDescriptor.read(input);
        }
    }

    @Test
    void generatesDescriptorProvidingFactoriesAndScopes() throws IOException {
        compiledClass("example/Service.class");

        ModuleInfoGenerator.generate(
                classes,
                "example.module",
                Map.of(FACTORY, List.of("example.$Service$$Factory"), SCOPE, List.of("example.ThreadScope")));

        assertThat(generatedDescriptor().provides())
                .extracting(Provides::service)
                .containsExactlyInAnyOrder(FACTORY, SCOPE);
    }

    @Test
    void requiresTheInjectModule() throws IOException {
        compiledClass("example/Service.class");

        ModuleInfoGenerator.generate(classes, "example.module", Map.of(FACTORY, List.of("example.$Service$$Factory")));

        assertThat(generatedDescriptor().requires()).extracting(Requires::name).contains("java.base", "be.imgn.inject");
    }

    @Test
    void exportsEveryPackageHoldingClasses() throws IOException {
        compiledClass("example/Service.class");
        compiledClass("example/nested/Helper.class");
        compiledClass("other/Thing.class");

        ModuleInfoGenerator.generate(classes, "example.module", Map.of());

        assertThat(generatedDescriptor().exports())
                .extracting(Exports::source)
                .containsExactlyInAnyOrder("example", "example.nested", "other");
    }

    @Test
    void omitsProvidesWhenThereAreNoProviders() throws IOException {
        compiledClass("example/Service.class");

        ModuleInfoGenerator.generate(classes, "example.module", Map.of());

        assertThat(generatedDescriptor().provides()).isEmpty();
    }
}
