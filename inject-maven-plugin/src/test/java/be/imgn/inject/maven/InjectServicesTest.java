package be.imgn.inject.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InjectServicesTest {

    private static final String FACTORY = "be.imgn.inject.context.Factory";
    private static final String SCOPE = "be.imgn.inject.context.Scope";

    @TempDir
    Path classes;

    private void serviceFile(String service, String content) throws IOException {
        Path file = classes.resolve("META-INF/services/" + service);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void readsBothFactoryAndScopeProviders() throws IOException {
        serviceFile(FACTORY, "example.$A$$Factory\nexample.$B$$Factory\n");
        serviceFile(SCOPE, "example.ThreadScope\n");

        assertThat(InjectServices.read(classes))
                .containsEntry(FACTORY, List.of("example.$A$$Factory", "example.$B$$Factory"))
                .containsEntry(SCOPE, List.of("example.ThreadScope"));
    }

    @Test
    void preservesServiceOrderFactoryThenScope() throws IOException {
        serviceFile(FACTORY, "example.$A$$Factory");
        serviceFile(SCOPE, "example.ThreadScope");

        assertThat(InjectServices.read(classes).keySet()).containsExactly(FACTORY, SCOPE);
    }

    @Test
    void omitsServicesWithNoProviders() throws IOException {
        serviceFile(FACTORY, "example.$A$$Factory");

        assertThat(InjectServices.read(classes)).containsOnlyKeys(FACTORY);
    }

    @Test
    void ignoresBlankLinesAndComments() throws IOException {
        serviceFile(FACTORY, """
                # generated
                example.$A$$Factory

                example.$B$$Factory # trailing
                """);

        assertThat(InjectServices.read(classes))
                .containsEntry(FACTORY, List.of("example.$A$$Factory", "example.$B$$Factory"));
    }

    @Test
    void returnsEmptyWhenNoServiceFilesExist() throws IOException {
        assertThat(InjectServices.read(classes)).isEmpty();
    }
}
