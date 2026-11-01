package be.imgn.inject.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Turns the Markdown pages of the guide into HTML pages.
 *
 * <p>The Markdown directory holds the pages and {@code pages.txt}, which lists them in the order of the navigation, one
 * file name per line; {@code index.md} is the home page. Each page needs a level-1 heading, its title. A link goes to
 * another page ({@code scopes.md}), to a heading ({@code scopes.md#custom-scopes} or {@code #custom-scopes}, by the
 * heading's identifier as GitHub writes it), to a class in the API documentation ({@code api:be.imgn.inject.Injector}),
 * or to a web page ({@code https://...}). Every other link is an error, and so are images and HTML outside code.
 *
 * <p>The theme directory holds {@code template.html}, where {@code {{title}}}, {@code {{navigation}}} and
 * {@code {{content}}} are replaced by those of each page, and the files to copy beside the pages, such as style sheets,
 * with their directories.
 *
 * <p>Every problem found, such as a broken link, is reported at once, and fails the generation.
 */
public final class SiteGenerator {

    private static final int ARGUMENTS = 3;
    private static final String PAGES = "pages.txt";
    private static final String HOME = "index.md";
    private static final String TEMPLATE = "template.html";
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private final List<String> problems = new ArrayList<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();

    private SiteGenerator() {}

    /**
     * Generates the guide.
     *
     * @param args the Markdown directory, the theme directory and the output directory
     * @throws IllegalArgumentException if there are not three arguments
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != ARGUMENTS) {
            throw new IllegalArgumentException("Usage: SiteGenerator <markdown directory> <theme directory> <output>");
        }
        generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    /**
     * Generates the guide.
     *
     * @throws IllegalStateException listing every problem found, if any
     */
    static void generate(Path markdown, Path theme, Path output) throws IOException {
        var generator = new SiteGenerator();
        generator.read(markdown);
        generator.resolveLinks();
        if (!generator.problems.isEmpty()) {
            throw new IllegalStateException("The guide has problems:\n  " + String.join("\n  ", generator.problems));
        }
        generator.write(theme, output);
    }

    private void read(Path markdown) throws IOException {
        var parser = Parser.builder().extensions(EXTENSIONS).build();
        var listed = Files.readAllLines(markdown.resolve(PAGES), UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        for (var name : listed) {
            var file = markdown.resolve(name);
            if (!name.endsWith(".md") || !Files.isRegularFile(file)) {
                problems.add(PAGES + ": no page " + name);
            } else if (pages.containsKey(name)) {
                problems.add(PAGES + ": " + name + " is listed twice");
            } else {
                var page = Page.parse(parser, file);
                if (page.title() == null) {
                    problems.add(name + ": no level-1 heading for the title");
                }
                pages.put(name, page);
            }
        }
        if (!pages.containsKey(HOME)) {
            problems.add(PAGES + ": no " + HOME);
        }
        for (var name : markdownFiles(markdown)) {
            if (!listed.contains(name)) {
                problems.add(name + ": not listed in " + PAGES);
            }
        }
    }

    private static Set<String> markdownFiles(Path markdown) throws IOException {
        try (var files = Files.list(markdown)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    private void resolveLinks() {
        for (var page : pages.values()) {
            page.document().accept(new AbstractVisitor() {
                @Override
                public void visit(Link link) {
                    resolve(page, link);
                    visitChildren(link);
                }

                @Override
                public void visit(Image image) {
                    problems.add(page.name() + ": images are not supported: " + image.getDestination());
                }

                // HTML would bypass the link check and the image ban. In a code block or code span, it is text.
                @Override
                public void visit(HtmlBlock html) {
                    problems.add(page.name() + ": HTML is not supported: " + firstLine(html.getLiteral()));
                }

                @Override
                public void visit(HtmlInline html) {
                    problems.add(page.name() + ": HTML is not supported: " + firstLine(html.getLiteral()));
                }
            });
        }
    }

    private static String firstLine(String text) {
        return text.strip().lines().findFirst().orElse("");
    }

    private void resolve(Page page, Link link) {
        var destination = link.getDestination();
        if (destination.startsWith("https://") || destination.startsWith("http://")) {
            return;
        }
        if (destination.startsWith(ApiLinks.SCHEME)) {
            var className = destination.substring(ApiLinks.SCHEME.length());
            ApiLinks.href(className)
                    .ifPresentOrElse(
                            link::setDestination,
                            () -> problems.add(page.name() + ": no API documentation for " + className));
            return;
        }
        var hash = destination.indexOf('#');
        var target = hash < 0 ? destination : destination.substring(0, hash);
        var anchor = hash < 0 ? null : destination.substring(hash + 1);
        var targetPage = target.isEmpty() ? page : pages.get(target);
        if (targetPage == null) {
            problems.add(page.name() + ": broken link to " + destination);
        } else if (anchor != null && !targetPage.hasAnchor(anchor)) {
            problems.add(page.name() + ": no heading " + anchor + " in " + targetPage.name());
        } else if (!target.isEmpty()) {
            link.setDestination(targetPage.htmlName() + (anchor == null ? "" : "#" + anchor));
        }
    }

    private void write(Path theme, Path output) throws IOException {
        var template = Files.readString(theme.resolve(TEMPLATE), UTF_8);
        Files.createDirectories(output);
        for (var page : pages.values()) {
            var renderer = HtmlRenderer.builder()
                    .extensions(EXTENSIONS)
                    .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                        if (node instanceof Heading heading) {
                            var id = page.id(heading);
                            if (id != null) {
                                attributes.put("id", id);
                            }
                        }
                    })
                    .build();
            var html = template.replace("{{title}}", escape(title(page)))
                    .replace("{{navigation}}", navigation(page))
                    .replace("{{content}}", renderer.render(page.document()));
            Files.writeString(output.resolve(page.htmlName()), html, UTF_8);
        }
        var templateFile = theme.resolve(TEMPLATE);
        try (var files = Files.walk(theme)) {
            for (var file : files.filter(file -> !file.equals(templateFile)).toList()) {
                var target = output.resolve(theme.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, REPLACE_EXISTING);
                }
            }
        }
    }

    private static String title(Page page) {
        var title = String.valueOf(page.title());
        return page.name().equals(HOME) ? title : title + " · imgn inject";
    }

    private String navigation(Page current) {
        var navigation = new StringBuilder("<ul>\n");
        for (var page : pages.values()) {
            navigation
                    .append("<li><a href=\"")
                    .append(page.htmlName())
                    .append('"')
                    .append(page == current ? " aria-current=\"page\"" : "")
                    .append('>')
                    .append(escape(String.valueOf(page.title())))
                    .append("</a></li>\n");
        }
        return navigation.append("</ul>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
