package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Untrusted text as Hephaestus posts it on the work: model text, quoted work and practice guidance. It escapes, and
 * parses Markdown only to keep code and link targets as they are. Outside them the text holds no HTML (every {@code <}
 * and {@code &} becomes a character reference), no image, no line that a GitLab quick action reads, and no name a
 * provider links: each becomes {@code <code>@name</code>}, which no backtick can pair with. The character reference
 * for the at sign is no escape: both providers link it. Only the bot's own template and markers, added after this
 * step, may hold HTML.
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
            Pattern.compile("(?i)https?://(?:[A-Za-z0-9_-]+\\.)*[A-Za-z0-9-]+\\.[A-Za-z0-9-]+(?:[/?#][^\\s<()]*)?");

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
            return ScmFeedbackFormatter.balanceCodeFences(escape(untrusted.replace("\r\n", "\n")));
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

    private static String escape(String text) {
        List<Range> verbatim = new ArrayList<>();
        Set<Integer> images = new HashSet<>();
        MARKDOWN.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Code code) {
                verbatim.add(span(code));
            }

            @Override
            public void visit(FencedCodeBlock block) {
                verbatim.add(span(block));
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                verbatim.add(span(block));
            }

            // The text of a link is untrusted text; its target and title are kept so the link still works. An
            // autolink to a web address is kept whole; any other autolink is escaped like the text around it.
            @Override
            public void visit(Link link) {
                Range whole = span(link);
                if (text.charAt(whole.from()) == '<') {
                    if (WEB_SCHEME.matcher(link.getDestination()).lookingAt()) verbatim.add(whole);
                    return;
                }
                target(link, whole);
                visitChildren(link);
            }

            // An image becomes a link to its picture, so nothing is fetched when the comment is read.
            @Override
            public void visit(Image image) {
                Range whole = span(image);
                images.add(whole.from());
                target(image, whole);
                visitChildren(image);
            }

            private void target(Node link, Range whole) {
                int position = whole.from();
                for (Node child = link.getFirstChild(); child != null; child = child.getNext())
                    position = Math.max(position, span(child).to());
                int target = text.indexOf("](", position);
                if (target >= 0 && target < whole.to()) verbatim.add(new Range(target, whole.to()));
            }
        });
        verbatim.sort((a, b) -> Integer.compare(a.from(), b.from()));

        StringBuilder out = new StringBuilder(text.length() + 64);
        int next = 0;
        boolean lineStart = true;
        for (int i = 0; i < text.length(); ) {
            while (next < verbatim.size() && verbatim.get(next).to() <= i) next++;
            if (next < verbatim.size() && verbatim.get(next).from() <= i) {
                Range kept = verbatim.get(next);
                out.append(text, i, kept.to());
                lineStart = text.charAt(kept.to() - 1) == '\n';
                i = kept.to();
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
            if (c == '!' && images.contains(i)) {
                out.append("\\!");
                i++;
                continue;
            }
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
            // An underscore before the name may close an emphasis, after which both providers link it.
            if (c == '@' && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
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

    private static boolean isAsciiPunctuation(char c) {
        return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && !Character.isISOControl(c);
    }
}
