package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
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

    // Both providers link @name after any character that cannot be part of a name; `@org/team` and `@group/subgroup`
    // name a team or a subgroup. A backtick before it is an earlier escape. A backslash does not stop the mention, so
    // it moves into the code with the name.
    private static final Pattern MENTION =
            Pattern.compile("(?<![A-Za-z0-9_`\\\\])\\\\?(@[A-Za-z0-9][A-Za-z0-9_-]*(?:[./][A-Za-z0-9_-]+)*)");

    // A bare web address renders as a link, and a name inside a link notifies no one.
    private static final Pattern BARE_LINK = Pattern.compile("(?i)(?:https?://|ftp://|www\\.)[^\\s<>\"'`\\\\]+");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private record Edit(int from, int to, String replacement) {}

    public static String escape(String body) {
        List<Edit> edits = new ArrayList<>();
        MARKDOWN.parse(body).accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                for (SourceSpan span : text.getSourceSpans())
                    outsideLinks(span.getInputIndex(), span.getInputIndex() + span.getLength(), "`", "`");
            }

            // Markdown does not render inside an HTML block, so only an HTML code element hides a name there.
            @Override
            public void visit(HtmlBlock block) {
                for (SourceSpan span : block.getSourceSpans()) {
                    int end = span.getInputIndex() + span.getLength();
                    Matcher tag = TAG.matcher(body).region(span.getInputIndex(), end);
                    int position = span.getInputIndex();
                    boolean code = false;
                    while (tag.find()) {
                        if (!code) outsideLinks(position, tag.start(), "<code>", "</code>");
                        String name = tag.group().toLowerCase(Locale.ROOT);
                        if (name.startsWith("<code")) code = true;
                        else if (name.startsWith("</code")) code = false;
                        position = tag.end();
                    }
                    if (!code) outsideLinks(position, end, "<code>", "</code>");
                }
            }

            private void outsideLinks(int from, int to, String open, String close) {
                if (from >= to) return;
                Matcher link = BARE_LINK.matcher(body).region(from, to);
                int position = from;
                while (link.find()) {
                    names(position, link.start(), open, close);
                    position = link.end();
                }
                names(position, to, open, close);
            }

            private void names(int from, int to, String open, String close) {
                if (from >= to) return;
                // Transparent bounds let the look-behind read the character before the region.
                Matcher name = MENTION.matcher(body).region(from, to).useTransparentBounds(true);
                while (name.find()) edits.add(new Edit(name.start(), name.end(), open + name.group(1) + close));
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
}
