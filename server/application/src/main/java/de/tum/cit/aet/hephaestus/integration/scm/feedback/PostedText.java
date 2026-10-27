package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * Untrusted text as Hephaestus posts it on the work: model text, quoted work and practice guidance. It escapes; it
 * trusts no parse to keep text as written, because a provider may read Markdown differently. Every {@code <} and
 * {@code &} becomes a character reference, a code span becomes an HTML code element built from its rendered text, a
 * link target is written again without a title, an image becomes a link, a reference definition and any line that a
 * GitLab quick action reads are neutralised, and every name a provider links becomes {@code <code>@name</code>},
 * which no backtick can pair with. The character reference for the at sign is no escape: both providers link it.
 * Only code blocks stay as written. Only the bot's own template and markers, added after this step, hold HTML.
 *
 * <p>It runs once on each untrusted text; every write and every readback comparison calls it with the same input.
 */
public final class PostedText {

    private PostedText() {}

    // Both providers link @name after a character that cannot be part of a name; `@org/team` and `@group/sub` name a
    // team or a subgroup. A name right after another (`@a@b`) is linked once the first is code, so one token runs to
    // the end of the chain.
    private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_][A-Za-z0-9_-]*(?:[./@][A-Za-z0-9_-]+)*");

    // GitHub links a bare address only after a line start, a space or `*_~(`, and only with a host whose last two
    // labels hold no underscore. A name in such an address notifies no one; every other name becomes code. The
    // address is cut at a parenthesis, which GitHub may or may not include, so a name after one becomes code.
    private static final Pattern WEB_ADDRESS =
            Pattern.compile("(?i)https?://(?:[A-Za-z0-9_-]+\\.)*[A-Za-z0-9-]+\\.[A-Za-z0-9-]+(?:[/?#][^\\s<()|]*)?");

    private static final Pattern WEB_SCHEME = Pattern.compile("(?i)https?://");

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[?!.,:*_~'\"]+$");

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /**
     * The untrusted text as it is posted.
     *
     * @throws FeedbackNotSentException if the text cannot be read; nothing was sent
     */
    public static String of(String untrusted) {
        try {
            return ScmFeedbackFormatter.balanceCodeFences(escape(normalized(untrusted)));
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("Feedback text could not be made safe to post", e);
        }
    }

    /** A summary as it is posted: its text made safe, then the hidden marker that finds it again. */
    public static String summary(SummaryContent content) {
        String marker = content.writeMarker();
        if (marker == null || marker.isBlank()) return of(content.body());
        return of(content.body().replace(marker, "").stripTrailing()) + "\n\n" + marker;
    }

    private record Range(int from, int to) {}

    /** A part of the text and what is written for it; {@code null} keeps the part as written. */
    private record Edit(int from, int to, @Nullable String replacement) {}

    // GitLab's quick action reader drops a lone carriage return, so every line end is one before anything else.
    private static final Pattern LINE_END = Pattern.compile("\\r\\n?|[\\u2028\\u2029]");

    // Direction controls can make the text read differently from the bytes a reader is shown.
    private static final Pattern DIRECTION_CONTROL = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");

    private static String normalized(String text) {
        return DIRECTION_CONTROL
                .matcher(LINE_END.matcher(text).replaceAll("\n"))
                .replaceAll("");
    }

    private static String escape(String text) {
        List<Edit> edits = new ArrayList<>();
        MARKDOWN.parse(text).accept(new AbstractVisitor() {
            // A provider may draw a code span's edges elsewhere (a table splits a row at each pipe first), so the span
            // is rebuilt as an HTML code element from its rendered text: whatever the provider decides, no unescaped
            // text reaches it. A line end inside a span renders as a space anyway.
            @Override
            public void visit(Code code) {
                Range whole = span(code);
                edits.add(new Edit(whole.from(), whole.to(), "<code>" + inert(code.getLiteral()) + "</code>"));
            }

            // A code block is line based; no provider reads a quick action, a name or HTML in it.
            @Override
            public void visit(FencedCodeBlock block) {
                Range whole = span(block);
                edits.add(new Edit(whole.from(), whole.to(), null));
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                Range whole = span(block);
                edits.add(new Edit(whole.from(), whole.to(), null));
            }

            // A definition renders as nothing, so its text would be hidden; the links that use it get its target.
            @Override
            public void visit(LinkReferenceDefinition definition) {
                Range whole = span(definition);
                int end = whole.to() < text.length() && text.charAt(whole.to()) == '\n' ? whole.to() + 1 : whole.to();
                edits.add(new Edit(whole.from(), end, ""));
            }

            // The text of a link is untrusted text. Its target is written again without a title; a target that is not
            // a web address leaves only the text.
            @Override
            public void visit(Link link) {
                Range whole = span(link);
                if (text.charAt(whole.from()) == '<') {
                    String destination = link.getDestination();
                    if (WEB_SCHEME.matcher(destination).lookingAt())
                        edits.add(new Edit(whole.from(), whole.to(), "<" + encoded(destination) + ">"));
                    return;
                }
                target(link, whole, "[", "[");
                visitChildren(link);
            }

            // An image becomes a link to its picture, so nothing is fetched when the comment is read.
            @Override
            public void visit(Image image) {
                Range whole = span(image);
                target(image, whole, "![", "\\![");
                visitChildren(image);
            }

            private void target(Node link, Range whole, String opener, String kept) {
                String destination = link instanceof Link l ? l.getDestination() : ((Image) link).getDestination();
                boolean web = WEB_SCHEME.matcher(destination).lookingAt();
                int position = whole.from() + opener.length();
                for (Node child = link.getFirstChild(); child != null; child = child.getNext())
                    position = Math.max(position, span(child).to());
                edits.add(new Edit(whole.from(), whole.from() + opener.length(), web ? kept : ""));
                edits.add(new Edit(position, whole.to(), web ? "](<" + encoded(destination) + ">)" : ""));
            }
        });
        edits.sort((a, b) -> Integer.compare(a.from(), b.from()));

        StringBuilder out = new StringBuilder(text.length() + 64);
        int next = 0;
        boolean lineStart = true;
        for (int i = 0; i < text.length(); ) {
            while (next < edits.size()
                    && edits.get(next).to() <= i
                    && edits.get(next).from() < i) next++;
            if (next < edits.size() && edits.get(next).from() <= i) {
                Edit edit = edits.get(next++);
                String written = edit.replacement() == null ? text.substring(i, edit.to()) : edit.replacement();
                out.append(written);
                if (!written.isEmpty()) lineStart = written.charAt(written.length() - 1) == '\n';
                i = Math.max(i, edit.to());
                continue;
            }
            char c = text.charAt(i);
            if (c == '\n') {
                out.append(c);
                lineStart = true;
                i++;
                continue;
            }
            if (lineStart && (c == ' ' || c == '\t')) {
                out.append(c);
                i++;
                continue;
            }
            // GitLab runs a quick action from any line that starts with a slash, whatever its name.
            if (lineStart && c == '/') {
                out.append("\\/");
                lineStart = false;
                i++;
                continue;
            }
            lineStart = false;
            if (c == '\\' && i + 1 < text.length()) {
                char escaped = text.charAt(i + 1);
                // An escaped `<`, `&` or name is written as it renders, so no backslash precedes the HTML put here.
                if (escaped == '<' || escaped == '&' || escaped == '@') {
                    i++;
                    continue;
                }
                if (isAsciiPunctuation(escaped)) {
                    out.append(c).append(escaped);
                    i += 2;
                    continue;
                }
            }
            if (c == '<') {
                out.append("&lt;");
                i++;
                continue;
            }
            if (c == '&') {
                out.append("&amp;");
                i++;
                continue;
            }
            if ((c == 'h' || c == 'H') && linkBoundary(text, i)) {
                Matcher address = WEB_ADDRESS.matcher(text).region(i, text.length());
                if (address.lookingAt()) {
                    int end = address.end();
                    Matcher tail = TRAILING_PUNCTUATION.matcher(text.substring(i, end));
                    if (tail.find()) end = i + tail.start();
                    out.append(text, i, end);
                    i = end;
                    continue;
                }
            }
            // Both providers bound a name with ASCII word characters: after `é`, a CJK character or an underscore that
            // closes an emphasis, it is linked.
            if (c == '@' && (i == 0 || !isAsciiAlphanumeric(text.charAt(i - 1)))) {
                Matcher name = MENTION.matcher(text).region(i, text.length());
                if (name.lookingAt()) {
                    out.append("<code>").append(name.group()).append("</code>");
                    i = name.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static Range span(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) throw new IllegalStateException("A Markdown node has no place in the text.");
        SourceSpan last = spans.getLast();
        return new Range(spans.getFirst().getInputIndex(), last.getInputIndex() + last.getLength());
    }

    private static boolean linkBoundary(String text, int index) {
        if (index == 0) return true;
        char before = text.charAt(index - 1);
        return Character.isWhitespace(before) || "*_~(".indexOf(before) >= 0;
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    // Inside an HTML code element Markdown still reads, so every punctuation mark is escaped; names become code too.
    private static String inert(String code) {
        StringBuilder out = new StringBuilder(code.length() + 16);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '@' && (i == 0 || !isAsciiAlphanumeric(code.charAt(i - 1)))) {
                Matcher name = MENTION.matcher(code).region(i, code.length());
                if (name.lookingAt()) {
                    out.append("<code>").append(name.group()).append("</code>");
                    i = name.end() - 1;
                    continue;
                }
            }
            if (c == '\n') out.append(' ');
            else if (c == '<') out.append("&lt;");
            else if (c == '&') out.append("&amp;");
            else if (c == '>') out.append("&gt;");
            else if (isAsciiPunctuation(c)) out.append('\\').append(c);
            else out.append(c);
        }
        return out.toString();
    }

    // A target is written in angle brackets; a name, a pipe or a bracket in it is percent-encoded, so no provider
    // reads any of it as text even where it draws the link differently.
    private static String encoded(String destination) {
        StringBuilder out = new StringBuilder(destination.length() + 8);
        for (int i = 0; i < destination.length(); i++) {
            char c = destination.charAt(i);
            switch (c) {
                case '@' -> out.append("%40");
                case '|' -> out.append("%7C");
                case '<' -> out.append("%3C");
                case '>' -> out.append("%3E");
                case ' ' -> out.append("%20");
                case '\n' -> out.append("%0A");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isAsciiPunctuation(char c) {
        return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && !Character.isISOControl(c);
    }
}
