package be.imgn.inject.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Provides;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

class ModuleInfoPatcherTest {

    private static final String FACTORY = "be.imgn.inject.context.Factory";
    private static final String SCOPE = "be.imgn.inject.context.Scope";

    @TempDir
    Path directory;

    /** Writes a minimal module-info.class, optionally already providing some services. */
    private Path moduleInfo(String moduleName, Map<String, List<String>> existingProvides) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule(moduleName, 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        module.visitExport("example", 0);
        existingProvides.forEach((service, impls) -> module.visitProvide(
                service.replace('.', '/'),
                impls.stream().map(name -> name.replace('.', '/')).toArray(String[]::new)));
        module.visitEnd();
        writer.visitEnd();
        Path file = directory.resolve("module-info.class");
        Files.write(file, writer.toByteArray());
        return file;
    }

    private static ModuleDescriptor read(Path moduleInfo) throws IOException {
        try (var input = Files.newInputStream(moduleInfo)) {
            return ModuleDescriptor.read(input);
        }
    }

    @Test
    void addsFactoryProvidesClauseToModuleWithout() throws IOException {
        Path file = moduleInfo("example.module", Map.of());

        assertThat(ModuleInfoPatcher.addProvides(file, Map.of(FACTORY, List.of("example.$Foo$$Factory"))))
                .isTrue();

        assertThat(read(file).provides())
                .singleElement()
                .returns(FACTORY, Provides::service)
                .returns(List.of("example.$Foo$$Factory"), Provides::providers);
    }

    @Test
    void registersBothFactoryAndScopeProviders() throws IOException {
        Path file = moduleInfo("example.module", Map.of());

        ModuleInfoPatcher.addProvides(
                file, Map.of(FACTORY, List.of("example.$Foo$$Factory"), SCOPE, List.of("example.ThreadScope")));

        assertThat(read(file).provides()).extracting(Provides::service).containsExactlyInAnyOrder(FACTORY, SCOPE);
    }

    @Test
    void replacesExistingFrameworkProvidesSoRebuildsDoNotAccumulate() throws IOException {
        Path file = moduleInfo("example.module", Map.of(FACTORY, List.of("example.$Stale$$Factory")));

        ModuleInfoPatcher.addProvides(file, Map.of(FACTORY, List.of("example.$Fresh$$Factory")));

        assertThat(read(file).provides())
                .singleElement()
                .returns(List.of("example.$Fresh$$Factory"), Provides::providers);
    }

    @Test
    void patchingIsIdempotent() throws IOException {
        Path file = moduleInfo("example.module", Map.of());
        Map<String, List<String>> providers =
                Map.of(FACTORY, List.of("example.$Foo$$Factory"), SCOPE, List.of("example.ThreadScope"));

        ModuleInfoPatcher.addProvides(file, providers);
        byte[] first = Files.readAllBytes(file);
        ModuleInfoPatcher.addProvides(file, providers);

        assertThat(Files.readAllBytes(file)).isEqualTo(first);
    }

    @Test
    void preservesTheRestOfTheDescriptor() throws IOException {
        Path file = moduleInfo("example.module", Map.of());

        ModuleInfoPatcher.addProvides(file, Map.of(FACTORY, List.of("example.$Foo$$Factory")));

        ModuleDescriptor descriptor = read(file);
        assertThat(descriptor.name()).isEqualTo("example.module");
        assertThat(descriptor.exports())
                .extracting(ModuleDescriptor.Exports::source)
                .containsExactly("example");
        assertThat(descriptor.requires())
                .extracting(ModuleDescriptor.Requires::name)
                .contains("java.base");
    }

    @Test
    void reportsNoChangeWhenThereIsNothingToAdd() throws IOException {
        Path file = moduleInfo("example.module", Map.of());

        assertThat(ModuleInfoPatcher.addProvides(file, Map.of())).isFalse();
        assertThat(read(file).provides()).isEmpty();
    }

    @Test
    void leavesUnrelatedProvidesUntouched() throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule("example.module", 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        module.visitProvide("java/util/spi/ToolProvider", "example/OtherProvider");
        module.visitEnd();
        writer.visitEnd();
        Path file = directory.resolve("module-info.class");
        Files.write(file, writer.toByteArray());

        ModuleInfoPatcher.addProvides(file, Map.of(FACTORY, List.of("example.$Foo$$Factory")));

        assertThat(read(file).provides())
                .extracting(Provides::service)
                .containsExactlyInAnyOrderElementsOf(Set.of("java.util.spi.ToolProvider", FACTORY));
    }
}
