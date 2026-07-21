package be.imgn.inject.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code ServiceLoader} registrations the framework relies on: the {@code Factory} file the annotation
 * processor writes, and the {@code Scope} file a project hand-maintains for any custom scope. Each becomes a
 * {@code provides} clause in the module descriptor.
 */
final class InjectServices {

    /** The service interfaces registered as module {@code provides}, in declaration order. */
    static final List<String> SERVICES = List.of("be.imgn.inject.context.Factory", "be.imgn.inject.context.Scope");

    private InjectServices() {}

    /**
     * {@return each framework service mapped to its registered provider class names, omitting services with none}
     *
     * <p>Providers are read from {@code META-INF/services/<service>}; blank lines and {@code #} comments are ignored,
     * matching the {@code ServiceLoader} file format. The returned map preserves {@link #SERVICES} order.
     *
     * @param classesDirectory the compiled output directory
     * @throws IOException if a service file cannot be read
     */
    static Map<String, List<String>> read(Path classesDirectory) throws IOException {
        Map<String, List<String>> providers = new LinkedHashMap<>();
        for (String service : SERVICES) {
            List<String> registered = readService(classesDirectory, service);
            if (!registered.isEmpty()) {
                providers.put(service, registered);
            }
        }
        return providers;
    }

    private static List<String> readService(Path classesDirectory, String service) throws IOException {
        Path serviceFile = classesDirectory.resolve("META-INF/services/" + service);
        if (!Files.isRegularFile(serviceFile)) {
            return List.of();
        }
        return Files.readAllLines(serviceFile, StandardCharsets.UTF_8).stream()
                .map(line -> {
                    int comment = line.indexOf('#');
                    return (comment < 0 ? line : line.substring(0, comment)).trim();
                })
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
