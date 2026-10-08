package be.imgn.inject.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adds the framework {@code provides} clauses ({@code Factory}, and {@code Scope} when present) to a compiled
 * {@code module-info.class}.
 *
 * <p>The compiled descriptor is patched rather than the source, so the build never writes into the user's source tree.
 * An existing clause for a framework service is replaced, making the goal idempotent across rebuilds. Clauses for other
 * services are left untouched.
 */
final class ModuleInfoPatcher {

    private static final int ASM_API = Opcodes.ASM9;

    private ModuleInfoPatcher() {}

    /**
     * Rewrites {@code moduleInfo} so it provides each framework service's registered providers.
     *
     * @param moduleInfo the compiled {@code module-info.class}
     * @param providers each framework service mapped to its provider class names
     * @return {@code true} if the descriptor changed
     * @throws IOException if the descriptor cannot be read or written
     */
    static boolean addProvides(Path moduleInfo, Map<String, List<String>> providers) throws IOException {
        byte[] original = Files.readAllBytes(moduleInfo);
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        ProvidesRewriter rewriter = new ProvidesRewriter(writer, providers);
        reader.accept(rewriter, 0);
        if (!rewriter.changed) {
            return false;
        }
        Files.write(moduleInfo, writer.toByteArray());
        return true;
    }

    /** Drops any existing framework {@code provides} and re-adds the current ones. */
    private static final class ProvidesRewriter extends ClassVisitor {
        private final Map<String, List<String>> providers;
        private boolean changed;

        ProvidesRewriter(ClassVisitor delegate, Map<String, List<String>> providers) {
            super(ASM_API, delegate);
            this.providers = providers;
        }

        @Override
        public @Nullable ModuleVisitor visitModule(String name, int access, @Nullable String version) {
            ModuleVisitor delegate = super.visitModule(name, access, version);
            if (delegate == null) {
                return null;
            }
            return new ModuleVisitor(ASM_API, delegate) {
                @Override
                public void visitProvide(String service, String... impls) {
                    if (providers.containsKey(binaryName(service))) {
                        // Re-added in visitEnd, so an earlier run's clause never accumulates duplicates.
                        changed = true;
                        return;
                    }
                    super.visitProvide(service, impls);
                }

                @Override
                public void visitEnd() {
                    providers.forEach((service, impls) -> {
                        super.visitProvide(
                                internalName(service),
                                impls.stream().map(this::internalName).toArray(String[]::new));
                        changed = true;
                    });
                    super.visitEnd();
                }

                private String internalName(String className) {
                    return className.replace('.', '/');
                }

                private String binaryName(String internalName) {
                    return internalName.replace('/', '.');
                }
            };
        }
    }
}
