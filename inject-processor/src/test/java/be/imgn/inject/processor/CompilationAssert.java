package be.imgn.inject.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;

/** AssertJ assertions on a {@link Compilation}. */
final class CompilationAssert extends AbstractAssert<CompilationAssert, Compilation> {

    private CompilationAssert(Compilation actual) {
        super(actual, CompilationAssert.class);
    }

    static CompilationAssert assertThat(Compilation actual) {
        return new CompilationAssert(actual);
    }

    /** Verifies that javac reported no error. */
    CompilationAssert succeeded() {
        isNotNull();
        if (!actual.success()) {
            failWithMessage("Expected the compilation to succeed, but it failed with:%n%s", describeDiagnostics());
        }
        return this;
    }

    /** Verifies that javac reported at least one error. */
    CompilationAssert failed() {
        isNotNull();
        if (actual.success()) {
            failWithMessage("Expected the compilation to fail, but it succeeded with:%n%s", describeDiagnostics());
        }
        return this;
    }

    /** Verifies that an error diagnostic's message contains {@code expected}. */
    CompilationAssert hadErrorContaining(String expected) {
        return hadDiagnosticContaining(Diagnostic.Kind.ERROR, expected);
    }

    /** Verifies that a note diagnostic's message contains {@code expected}. */
    CompilationAssert hadNoteContaining(String expected) {
        return hadDiagnosticContaining(Diagnostic.Kind.NOTE, expected);
    }

    /** Verifies that the source of {@code qualifiedName} was generated, and returns assertions on its content. */
    AbstractStringAssert<?> generatedSourceFile(String qualifiedName) {
        isNotNull();
        JavaFileObject file = actual.generatedSourceFile(qualifiedName)
                .orElseThrow(() -> failure(
                        "Expected a generated source for %s, but only these were generated:%n%s",
                        qualifiedName,
                        actual.generatedSourceFiles().stream()
                                .map(JavaFileObject::getName)
                                .collect(Collectors.joining(System.lineSeparator()))));
        try {
            return Assertions.assertThat(file.getCharContent(true).toString())
                    .as("generated source of %s", qualifiedName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CompilationAssert hadDiagnosticContaining(Diagnostic.Kind kind, String expected) {
        isNotNull();
        List<Diagnostic<? extends JavaFileObject>> matching = actual.diagnostics(kind);
        if (matching.stream()
                .noneMatch(diagnostic -> diagnostic.getMessage(Locale.ROOT).contains(expected))) {
            failWithMessage(
                    "Expected a %s containing:%n  %s%nbut the diagnostics were:%n%s",
                    kind, expected, describeDiagnostics());
        }
        return this;
    }

    private String describeDiagnostics() {
        if (actual.diagnostics().isEmpty()) {
            return "  (none)";
        }
        return actual.diagnostics().stream()
                .map(diagnostic -> "  " + diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
