package de.tum.cit.aet.hephaestus.integration.scm.feedback;

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
 * Turns every GitHub or GitLab user, team or group mention in a body that Hephaestus writes into code, so neither a
 * comment nor a later edit of it notifies anyone by name. The composer already refuses a mention in model-written
 * prose; this covers every other text that reaches the provider.
 */
public final class ProviderMentions {

    private ProviderMentions() {}

    // Both providers link @name at the start of a rendered text run or after a character that cannot be part of a
    // name; `@org/team` and `@group/subgroup` name a team or a subgroup.
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@[A-Za-z0-9][A-Za-z0-9_-]*(?:[./][A-Za-z0-9_-]+)*");

    // A bare web address renders as a link, and a name inside a link notifies no one. An autolink ends before `)`.
    private static final Pattern BARE_LINK = Pattern.compile("(?i)(?:https?://|ftp://|www\\.)[^\\s<>\"'`\\\\)]+");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final Pattern BACKTICKS = Pattern.compile("`+");

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private record Edit(int from, int to, String replacement) {}

    public static String escape(String body) {
        List<Edit> edits = new ArrayList<>();
        boolean[] unplaced = {false};
        MARKDOWN.parse(body).accept(new AbstractVisitor() {
            // The rendered text decides: `&#64;name` and a name right after other inline markup are mentions too.
            @Override
            public void visit(Text text) {
                if (text.getPrevious() instanceof HtmlInline open
                        && open.getLiteral().equalsIgnoreCase("<code>")) return;
                String literal = text.getLiteral();
                List<int[]> names = names(literal, 0, literal.length());
                if (names.isEmpty()) return;
                List<SourceSpan> spans = text.getSourceSpans();
                if (spans.isEmpty()) {
                    unplaced[0] = true;
                    return;
                }
                int from = spans.getFirst().getInputIndex();
                int to = spans.getLast().getInputIndex() + spans.getLast().getLength();
                if (body.substring(from, to).equals(literal)) {
                    for (int[] name : names) {
                        int start = from + name[0];
                        int end = from + name[1];
                        String found = body.substring(start, end);
                        // Next to a code span, a backtick fence would join it; an HTML code element stays apart.
                        boolean besideCode = (start > 0 && body.charAt(start - 1) == '`')
                                || (end < body.length() && body.charAt(end) == '`');
                        edits.add(new Edit(start, end, besideCode ? "<code>" + found + "</code>" : code(found)));
                    }
                } else {
                    // Escapes or character references move the name in the source; the whole run becomes code.
                    edits.add(new Edit(from, to, code(literal)));
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
        if (unplaced[0]) return MENTION.matcher(body).replaceAll(match -> code(match.group()));
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

    // A fence one backtick longer than any run inside keeps the text whole; spaces keep an edge backtick apart.
    private static String code(String text) {
        int longest = 0;
        Matcher run = BACKTICKS.matcher(text);
        while (run.find()) longest = Math.max(longest, run.end() - run.start());
        String fence = "`".repeat(longest + 1);
        boolean pad = text.startsWith("`") || text.endsWith("`");
        return fence + (pad ? " " : "") + text + (pad ? " " : "") + fence;
    }
}
