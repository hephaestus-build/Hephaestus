package de.tum.cit.aet.hephaestus.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * The link rule for prose a model wrote, on every surface that shows it as Markdown (OWASP LLM05: encode output for
 * where it is used). A link stays a link only when it points to an allowed host: the workspace's providers or
 * Hephaestus itself. Any other link, image or web address becomes inline code, so the reader still sees where it
 * pointed but cannot follow it by a click, and a query string cannot carry context out. Slack's own mention and link
 * syntax ({@code <@U1>}, {@code <!here>}, {@code <https://x|label>}) becomes inline code as well.
 *
 * <p>A renderer other than CommonMark, such as Slack's, may read a block differently. So only an inline code span is
 * taken as text: in raw HTML, link reference definitions and code blocks, each outside address becomes code too.
 *
 * <p>The web app applies the same rule in {@code UntrustedMarkdown}.
 */
public final class ModelLinks {

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    // Slack user, group, channel, broadcast and link syntax, and any angle-bracketed address.
    private static final Pattern SLACK_SYNTAX = Pattern.compile("<[@#!][^<>\\s]*>|<[a-zA-Z][a-zA-Z0-9+.-]*:[^<>\\s]*>");

    // A web address that Slack and GitHub-flavoured Markdown may turn into a link.
    private static final Pattern WEB_ADDRESS =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:https?://|www\\.)[^\\s<>\"'`\\\\]+");

    private static final Pattern BACKTICKS = Pattern.compile("`+");

    private ModelLinks() {}

    /**
     * The text with every link to a host outside {@code allowedHosts} turned into inline code.
     *
     * @param allowedHosts lower-case host names; a host written as {@code .host} also allows its subdomains
     */
    public static String neutralize(String markdown, Set<String> allowedHosts) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        var edits = new ArrayList<Edit>();
        var lines = lineStarts(text);
        MARKDOWN.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                if (allowed(link.getDestination(), allowedHosts)) visitChildren(link);
                else replace(link, labelled(text, link, link.getDestination(), lines, allowedHosts));
            }

            @Override
            public void visit(Image image) {
                replace(image, labelled(text, image, image.getDestination(), lines, allowedHosts));
            }

            @Override
            public void visit(Text node) {
                replaceDefanged(node);
            }

            @Override
            public void visit(HtmlInline html) {
                // `<!here>` and `<https://x|label>` read as HTML here, but Slack reads them as its own syntax.
                replace(html, code(html.getLiteral()));
            }

            @Override
            public void visit(HtmlBlock html) {
                replaceDefanged(html);
            }

            @Override
            public void visit(LinkReferenceDefinition definition) {
                replaceDefanged(definition);
            }

            @Override
            public void visit(FencedCodeBlock block) {
                replaceDefanged(block);
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                replaceDefanged(block);
            }

            @Override
            public void visit(Code code) {}

            private void replaceDefanged(Node node) {
                int start = offset(node, lines);
                if (start < 0) return;
                String source = text.substring(start, end(node, lines));
                String defanged = defang(source, allowedHosts);
                if (!defanged.equals(source)) edits.add(new Edit(start, end(node, lines), defanged));
            }

            private void replace(Node node, String replacement) {
                int start = offset(node, lines);
                if (start >= 0) edits.add(new Edit(start, end(node, lines), replacement));
            }
        });
        if (edits.isEmpty()) return text;
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        var result = new StringBuilder(text);
        int limit = Integer.MAX_VALUE;
        for (Edit edit : edits) {
            if (edit.end() > limit) continue;
            result.replace(edit.start(), edit.end(), edit.replacement());
            limit = edit.start();
        }
        return result.toString();
    }

    /** Whether {@code url} is an absolute web address on an allowed host. */
    public static boolean allowed(String url, Set<String> allowedHosts) {
        String host = host(url);
        if (host == null) return false;
        for (String allowed : allowedHosts) {
            boolean match = allowed.startsWith(".")
                    ? host.equals(allowed.substring(1)) || host.endsWith(allowed)
                    : host.equals(allowed);
            if (match) return true;
        }
        return false;
    }

    /** Slack syntax and each web address outside the allowed hosts, as inline code; the rest unchanged. */
    private static String defang(String source, Set<String> allowedHosts) {
        var out = new StringBuilder();
        int at = 0;
        Matcher slack = SLACK_SYNTAX.matcher(source);
        Matcher web = WEB_ADDRESS.matcher(source);
        while (at < source.length()) {
            boolean slackFound = slack.find(at);
            int slackStart = slackFound ? slack.start() : Integer.MAX_VALUE;
            boolean webFound = web.find(at);
            int webStart = webFound ? web.start() : Integer.MAX_VALUE;
            if (!slackFound && !webFound) break;
            if (slackStart <= webStart) {
                out.append(source, at, slackStart).append(code(slack.group()));
                at = slack.end();
                continue;
            }
            String address = trimmed(web.group());
            out.append(source, at, webStart);
            out.append(allowed(address, allowedHosts) ? address : code(address));
            at = webStart + address.length();
        }
        return out.append(source.substring(at)).toString();
    }

    private static @Nullable String host(String url) {
        String candidate = url.regionMatches(true, 0, "www.", 0, 4) ? "https://" + url : url;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            if (uri.getRawUserInfo() != null) return null;
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** A link's own words, under the same rule, followed by its address as code. */
    private static String labelled(
            String text, Node node, String destination, List<Integer> lines, Set<String> allowedHosts) {
        Node first = node.getFirstChild();
        Node last = node.getLastChild();
        String label = "";
        if (first != null && last != null) {
            int from = offset(first, lines);
            int to = end(last, lines);
            if (from >= 0 && to >= from) label = text.substring(from, to).strip();
        }
        if (label.isEmpty() || label.equals(destination)) return code(destination);
        return neutralize(label, allowedHosts) + " (" + code(destination) + ")";
    }

    /** Inline code that holds {@code text} whatever backticks it contains. */
    static String code(String text) {
        int longest = 0;
        Matcher run = BACKTICKS.matcher(text);
        while (run.find()) longest = Math.max(longest, run.group().length());
        String fence = "`".repeat(longest + 1);
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + pad + text + pad + fence;
    }

    /** A web address ends before trailing punctuation, and before a closing parenthesis that it does not open. */
    private static String trimmed(String link) {
        int end = link.length();
        while (end > 0) {
            char last = link.charAt(end - 1);
            boolean unmatched = last == ')' && count(link, end, ')') > count(link, end, '(');
            if (".,:;!?*_~]".indexOf(last) >= 0 || unmatched) end--;
            else break;
        }
        return link.substring(0, end);
    }

    private static long count(String text, int end, char wanted) {
        return text.substring(0, end).chars().filter(c -> c == wanted).count();
    }

    private static List<Integer> lineStarts(String text) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts;
    }

    private static int offset(Node node, List<Integer> lines) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) return -1;
        SourceSpan first = spans.getFirst();
        return lines.get(first.getLineIndex()) + first.getColumnIndex();
    }

    private static int end(Node node, List<Integer> lines) {
        List<SourceSpan> spans = node.getSourceSpans();
        SourceSpan last = spans.getLast();
        return lines.get(last.getLineIndex()) + last.getColumnIndex() + last.getLength();
    }

    private record Edit(int start, int end, String replacement) {}
}
