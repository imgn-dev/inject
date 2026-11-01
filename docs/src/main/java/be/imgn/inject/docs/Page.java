package be.imgn.inject.docs;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/** A Markdown page of the guide, parsed, with an identifier for each of its headings. */
final class Page {

    private final String name;
    private final Node document;
    private final @Nullable String title;
    private final Map<Heading, String> headingIds;

    private Page(String name, Node document, @Nullable String title, Map<Heading, String> headingIds) {
        this.name = name;
        this.document = document;
        this.title = title;
        this.headingIds = headingIds;
    }

    static Page parse(Parser parser, Path file) throws IOException {
        var document = parser.parse(Files.readString(file, UTF_8));
        var headings = new HeadingCollector();
        document.accept(headings);
        return new Page(file.getFileName().toString(), document, headings.title, headings.ids);
    }

    /** The name of the Markdown file, such as {@code scopes.md}. */
    String name() {
        return name;
    }

    /** The name of the HTML file, such as {@code scopes.html}. */
    String htmlName() {
        return name.substring(0, name.length() - ".md".length()) + ".html";
    }

    Node document() {
        return document;
    }

    /** The text of the first level-1 heading, if any. */
    @Nullable
    String title() {
        return title;
    }

    @Nullable
    String id(Heading heading) {
        return headingIds.get(heading);
    }

    boolean hasAnchor(String id) {
        return headingIds.containsValue(id);
    }

    /**
     * Returns the identifier of a heading: its text in lowercase, with spaces as hyphens and without the characters
     * that are neither letters, digits, hyphens nor underscores, as GitHub writes them.
     */
    static String slug(String text) {
        var slug = new StringBuilder();
        text.strip().toLowerCase(Locale.ROOT).codePoints().forEach(c -> {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                slug.appendCodePoint(c);
            } else if (Character.isWhitespace(c)) {
                slug.append('-');
            }
        });
        return slug.toString();
    }

    private static final class HeadingCollector extends AbstractVisitor {

        private final Map<Heading, String> ids = new HashMap<>();
        private final Set<String> used = new HashSet<>();
        private @Nullable String title;

        @Override
        public void visit(Heading heading) {
            var text = new StringBuilder();
            heading.accept(new TextCollector(text));
            if (title == null && heading.getLevel() == 1) {
                title = text.toString().strip();
            }
            var slug = slug(text.toString());
            var id = slug;
            for (var n = 1; !used.add(id); n++) {
                id = slug + "-" + n;
            }
            ids.put(heading, id);
        }
    }

    private static final class TextCollector extends AbstractVisitor {

        private final StringBuilder text;

        TextCollector(StringBuilder text) {
            this.text = text;
        }

        @Override
        public void visit(Text node) {
            text.append(node.getLiteral());
        }

        @Override
        public void visit(Code node) {
            text.append(node.getLiteral());
        }
    }
}
