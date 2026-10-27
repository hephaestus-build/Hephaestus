package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Turns every GitHub or GitLab user, team or group mention in a body that Hephaestus writes into an HTML code element,
 * so neither a comment nor a later edit of it notifies anyone by name. The composer already refuses a mention in
 * model-written prose; this covers every other text that reaches the provider. An HTML code element, unlike a backtick
 * fence, cannot pair with a backtick elsewhere in the body, and both providers link no name inside it. The character
 * reference for the at sign is no escape: GitHub links it.
 */
public final class ProviderMentions {

    private ProviderMentions() {}

    // Both providers link @name at the start of a rendered text run or after a character that cannot be part of a
    // name; `@org/team` and `@group/subgroup` name a team or a subgroup. A name right after another (`@a@b`) is linked
    // once the first is code, so one token runs to the end of the chain.
    private static final Pattern MENTION =
            Pattern.compile("(?<![A-Za-z0-9_])@[A-Za-z0-9_][A-Za-z0-9_-]*(?:[./@][A-Za-z0-9_-]+)*");

    // A bare web address with a host renders as a link, and a name inside a link notifies no one. `https://@name`
    // has no host and stays a mention. An autolink ends before `)`.
    private static final Pattern BARE_LINK =
            Pattern.compile("(?i)(?:https?://|ftp://|www\\.)[A-Za-z0-9][^\\s<>\"'`\\\\)]*");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    // Markdown punctuation that would still act inside an HTML code element, and the characters HTML would read.
    private static final Pattern MARKDOWN_PUNCTUATION = Pattern.compile("[!\"#$%'()*+,\\-./:;=?@\\[\\\\\\]^_`{|}~]");

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private record Edit(int from, int to, String replacement) {}

    /**
     * The body with every mention in an HTML code element. Escaping its own output changes nothing.
     *
     * @throws FeedbackNotSentException if the body cannot be read as Markdown; nothing was sent
     */
    public static String escape(String body) {
        try {
            return escapeMarkdown(body);
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("Feedback text could not be checked for mentions", e);
        }
    }

    private static String escapeMarkdown(String body) {
        List<Edit> edits = new ArrayList<>();
        MARKDOWN.parse(body).accept(new AbstractVisitor() {
            // The rendered text decides: `\\@name` and a name right after other inline markup are mentions too.
            @Override
            public void visit(Text text) {
                if (text.getPrevious() instanceof HtmlInline open
                        && open.getLiteral().equalsIgnoreCase("<code>")) return;
                String literal = text.getLiteral();
                List<int[]> names = names(literal, 0, literal.length());
                if (names.isEmpty()) return;
                List<SourceSpan> spans = text.getSourceSpans();
                if (spans.isEmpty()) throw new IllegalStateException("A text run has no place in the body.");
                int to = spans.getLast().getInputIndex() + spans.getLast().getLength();
                String source = body.substring(spans.getFirst().getInputIndex(), to);
                // A paragraph drops the spaces around its text, which the span may still hold.
                int from = spans.getFirst().getInputIndex()
                        + (source.equals(literal)
                                ? 0
                                : source.length() - source.stripLeading().length());
                if (source.equals(literal) || source.strip().equals(literal)) {
                    for (int[] name : names)
                        edits.add(new Edit(
                                from + name[0],
                                from + name[1],
                                "<code>" + literal.substring(name[0], name[1]) + "</code>"));
                } else {
                    // Escapes or character references move the name in the source; the whole run becomes code.
                    edits.add(new Edit(spans.getFirst().getInputIndex(), to, code(literal)));
                }
            }

            // Markdown does not render inside an HTML block, so only an HTML code element hides a name there.
            @Override
            public void visit(HtmlBlock block) {
                List<SourceSpan> spans = block.getSourceSpans();
                if (spans.isEmpty()) return;
                int end = spans.getLast().getInputIndex() + spans.getLast().getLength();
                Matcher tag = TAG.matcher(body).region(spans.getFirst().getInputIndex(), end);
                int position = spans.getFirst().getInputIndex();
                boolean inCode = false;
                while (tag.find()) {
                    if (!inCode) html(position, tag.start());
                    String name = tag.group().toLowerCase(Locale.ROOT);
                    if (name.startsWith("<code")) inCode = true;
                    else if (name.startsWith("</code")) inCode = false;
                    position = tag.end();
                }
                if (!inCode) html(position, end);
            }

            private void html(int from, int to) {
                for (int[] name : names(body, from, to))
                    edits.add(new Edit(name[0], name[1], "<code>" + body.substring(name[0], name[1]) + "</code>"));
            }
        });
        if (edits.isEmpty()) return body;
        edits.sort(Comparator.comparingInt(Edit::from));
        StringBuilder out = new StringBuilder(body.length() + edits.size() * 13);
        int position = 0;
        for (Edit edit : edits) {
            if (edit.from() < position) continue;
            out.append(body, position, edit.from()).append(edit.replacement());
            position = edit.to();
        }
        return out.append(body, position, body.length()).toString();
    }

    /** The name ranges in {@code text} between {@code from} and {@code to}, outside bare web addresses. */
    private static List<int[]> names(String text, int from, int to) {
        List<int[]> found = new ArrayList<>();
        Matcher link = BARE_LINK.matcher(text).region(from, to);
        int position = from;
        while (true) {
            boolean more = link.find();
            int end = more ? link.start() : to;
            if (position < end) {
                Matcher name = MENTION.matcher(text).region(position, end);
                while (name.find()) found.add(new int[] {name.start(), name.end()});
            }
            if (!more) return found;
            position = link.end();
        }
    }

    // Markdown still reads inside an HTML code element, so a whole run's punctuation is escaped and it renders as
    // written. A name alone needs none: its characters start no Markdown.
    private static String code(String text) {
        String inert =
                MARKDOWN_PUNCTUATION.matcher(text).replaceAll(match -> Matcher.quoteReplacement("\\" + match.group()));
        return "<code>" + inert.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</code>";
    }
}
