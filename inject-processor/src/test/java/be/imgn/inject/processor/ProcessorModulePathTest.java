package be.imgn.inject.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs javac with the processor on the processor module path, where only the module descriptor makes it found. The
 * other tests hand the processor to javac directly. javac runs in its own process: in process, the processor's module
 * layer would also see the test class path.
 */
class ProcessorModulePathTest {

    @Test
    void javacFindsTheProcessorOnTheProcessorModulePath(@TempDir Path directory)
            throws IOException, InterruptedException {
        Path source = directory.resolve("example/Widget.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                import be.imgn.inject.Inject;

                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Map<String, ModuleReference> modules = modulesOnTheClassPath();

        Process javac = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                        "-proc:full",
                        "--processor-module-path",
                        modulePath(modules, "be.imgn.inject.processor"),
                        "-classpath",
                        modulePath(modules, "be.imgn.inject"),
                        "-d",
                        classes.toString(),
                        source.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(javac.waitFor()).as(output).isZero();
        assertThat(classes.resolve("example/$Widget$$Factory.class")).exists();
        assertThat(classes.resolve("META-INF/services/be.imgn.inject.context.Factory"))
                .content()
                .contains("example.$Widget$$Factory");
    }

    /** {@return the modules on the class path the tests run with, by name} */
    private static Map<String, ModuleReference> modulesOnTheClassPath() {
        Map<String, ModuleReference> modules = new HashMap<>();
        Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path"))
                .map(Path::of)
                // A directory is a module only with a descriptor; the test classes have none.
                .filter(entry -> !Files.isDirectory(entry) || Files.exists(entry.resolve("module-info.class")))
                .flatMap(entry -> ModuleFinder.of(entry).findAll().stream())
                .forEach(module -> modules.put(module.descriptor().name(), module));
        return modules;
    }

    /** {@return the module path holding {@code root} and the modules it requires, outside the JDK} */
    private static String modulePath(Map<String, ModuleReference> modules, String root) {
        Set<Path> modulePath = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (!seen.add(name) || ModuleFinder.ofSystem().find(name).isPresent()) {
                continue;
            }
            ModuleReference module = modules.get(name);
            if (module == null) {
                throw new IllegalStateException("Module " + name + " is not on the class path");
            }
            modulePath.add(Path.of(module.location().orElseThrow()));
            module.descriptor().requires().stream()
                    .filter(requires -> !requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                    .map(ModuleDescriptor.Requires::name)
                    .forEach(pending::add);
        }
        return modulePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
