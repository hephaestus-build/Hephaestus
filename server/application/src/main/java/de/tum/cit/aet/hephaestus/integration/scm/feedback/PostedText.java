package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Untrusted text as Hephaestus posts it on the work: model text, quoted work, practice guidance and repository paths.
 * Of the Markdown it holds, it keeps code spans and emphasis, written again by Hephaestus in one form that GitHub and
 * GitLab read the same way. Everything else is literal text: no fence, quote, list, heading, footnote, link, image,
 * table, HTML or GitLab quick action, because the two providers read those in dialects that no parser here matches.
 *
 * <p>Literal text escapes only what could open structure. {@code &}, {@code <} and {@code >} become character
 * references. A backslash goes before each of {@code \ ` * _ ~ [ ] | $ / @ { } ! :}, before a dot that a letter or digit
 * follows (so no web or e-mail address becomes a link), and before the punctuation that starts a line or ends the
 * number at its start (so no line becomes a block). Every name a provider links and every issue or merge request
 * reference becomes code: {@code <code>\@name</code>}. A character reference or a backslash before the at sign or the
 * number sign is no escape: the providers link through them. Inside a code span the providers link nothing, so its
 * text stays as written. Only the bot's own template and markers hold other structure.
 *
 * <p>It runs once on each untrusted text; every write and every readback comparison calls it with the same input.
 */
public final class PostedText {

    private PostedText() {}

    // Both providers link @name after a character that is no ASCII letter or digit; `@org/team` and `@group/sub` name
    // a team or a subgroup. A name right after another (`@a@b`) is linked once the first is code, so one token runs
    // to the end of the chain.
    private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_][A-Za-z0-9_-]*(?:[./@][A-Za-z0-9_-]+)*");

    // A reference makes the referenced item list the comment, and GitHub reads it through a backslash. Issue, pull
    // request, merge request, epic and snippet numbers, `GH-` numbers, cross-repository forms, labels and milestones
    // become code like names.
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)?[#!&$]\\d+|GH-\\d+|[~%](?:\"[^\"\\n]*\"|[A-Za-z0-9_][A-Za-z0-9_.:-]*)");

    // Emphasis, code, links, images, HTML, strikethrough, tables, GitLab math and diff marks, autolinks, quick actions
    // and names. GitLab reads `[text](address)` again in the rendered text, through escaped brackets: only an escaped
    // `!` keeps it from an image and an escaped `:` from an absolute link. Other punctuation opens nothing in a line.
    private static final String ALWAYS_ESCAPED = "\\`*_~[]|$/@{}!:";

    // GitHub takes at most 65,536 characters in a comment and GitLab more; the escaped text leaves room for the header
    // and the hidden markers.
    static final int MAX_POSTED_TEXT = 60_000;

    static final String SHORTENED = "\n\n(Shortened to fit the comment length limit.)";

    // GitLab's quick action reader drops a lone carriage return, so every line end is one before anything else.
    private static final Pattern LINE_END = Pattern.compile("\\r\\n?|[\\u2028\\u2029\\u0085]");

    // Direction controls, zero-width characters and tag characters make the text read differently from its bytes.
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\x{E0000}-\\x{E007F}]");

    // Each line is read alone and with no block parser, so only its inline Markdown has a meaning.
    private static final Parser INLINE = Parser.builder()
            .enabledBlockTypes(Set.of())
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /** One line of untrusted text, as literal text: its line ends become spaces. */
    public static String plain(String untrusted) {
        return literal(untrusted, true);
    }

    /** Untrusted text shown as code: an HTML code element around the literal text, which no backtick can end. */
    public static String inlineCode(String untrusted) {
        return "<code>" + plain(untrusted) + "</code>";
    }

    /**
     * Untrusted text of several lines, with its code spans and emphasis, that keeps its line breaks. Leading spaces
     * are dropped, so no line becomes code.
     */
    public static String of(String untrusted) {
        String text = normalized(untrusted);
        String posted = lines(text);
        if (posted.length() <= MAX_POSTED_TEXT) return posted;
        // Escaping lengthens the text, so the cut is found on the escaped result and made in the text before it, at
        // the last space before the longest prefix that fits.
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (lines(text.substring(0, middle)).length() + SHORTENED.length() <= MAX_POSTED_TEXT) low = middle;
            else high = middle - 1;
        }
        int cut = Math.max(text.lastIndexOf(' ', low), text.lastIndexOf('\n', low));
        if (cut <= 0) cut = low;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        return lines(text.substring(0, cut)) + SHORTENED;
    }

    /**
     * A summary before its disclosure footer: the bot's own header, the posted text and its physical marker.
     */
    public static String summary(SummaryContent content) {
        String marker = content.writeMarker();
        String body = marker == null || marker.isBlank()
                ? content.body()
                : content.body().replace(marker, "");
        String header = content.header();
        String posted = (header == null ? "" : header + "\n\n") + of(body);
        return marker == null || marker.isBlank() ? posted : posted + "\n\n" + marker;
    }

    private static String lines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i].strip();
            if (!line.isEmpty()) new Line(line, out).children(INLINE.parse(line));
        }
        return out.toString().strip();
    }

    private static String normalized(String text) {
        return INVISIBLE.matcher(LINE_END.matcher(text).replaceAll("\n")).replaceAll("");
    }

    /** Writes one parsed line: its code spans and emphasis again, and the rest of it as literal text. */
    private record Line(String source, StringBuilder out, StringBuilder text) {

        Line(String source, StringBuilder out) {
            this(source, out, new StringBuilder());
        }

        void children(Node parent) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                switch (node) {
                    case Document document -> children(document);
                    case Paragraph paragraph -> children(paragraph);
                    case Text words -> text.append(words.getLiteral());
                    case Code code -> {
                        flush();
                        out.append(codeSpan(code.getLiteral()));
                    }
                    case Emphasis emphasis -> delimited("*", emphasis);
                    case StrongEmphasis strong -> delimited("**", strong);
                    // A link, an image, HTML: the characters the author wrote, as literal text.
                    default -> text.append(written(node));
                }
            }
            flush();
        }

        private void delimited(String delimiter, Node node) {
            flush();
            out.append(delimiter);
            children(node);
            out.append(delimiter);
        }

        private String written(Node node) {
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) return "";
            SourceSpan last = spans.getLast();
            return source.substring(spans.getFirst().getInputIndex(), last.getInputIndex() + last.getLength());
        }

        private void flush() {
            if (text.isEmpty()) return;
            boolean lineStart = out.isEmpty() || out.charAt(out.length() - 1) == '\n';
            out.append(literal(text.toString(), lineStart));
            text.setLength(0);
        }
    }

    /** A code span that holds the text as written: its fence is longer than any run of backticks inside it. */
    private static String codeSpan(String code) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < code.length(); i++) {
            run = code.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        // CommonMark drops one space at each end when both ends hold one, and a backtick at an end would join the
        // fence.
        boolean padded = code.startsWith("`")
                || code.endsWith("`")
                || (code.startsWith(" ") && code.endsWith(" ") && !code.isBlank());
        return padded ? fence + " " + code + " " + fence : fence + code + fence;
    }

    /**
     * Text that renders as itself; at a line's start a block would open, so more is escaped there. The parser decodes
     * character references, so a decoded line end or hidden character is taken out again here.
     */
    private static String literal(String untrusted, boolean lineStart) {
        String text = normalized(untrusted).replace('\n', ' ');
        StringBuilder out = new StringBuilder(text.length() + 16);
        int leadingDigits = 0;
        while (lineStart && leadingDigits < text.length() && isAsciiDigit(text.charAt(leadingDigits))) leadingDigits++;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '@' && (i == 0 || !isAsciiAlphanumeric(text.charAt(i - 1)))) {
                Matcher name = MENTION.matcher(text).region(i, text.length());
                if (name.lookingAt()) {
                    out.append("<code>")
                            .append(punctuationEscaped(name.group()))
                            .append("</code>");
                    i = name.end() - 1;
                    continue;
                }
            }
            if (i == 0 || !isAsciiAlphanumeric(text.charAt(i - 1))) {
                Matcher reference = REFERENCE.matcher(text).region(i, text.length());
                if (reference.lookingAt()
                        && (reference.end() == text.length() || !isAsciiAlphanumeric(text.charAt(reference.end())))) {
                    out.append("<code>")
                            .append(punctuationEscaped(reference.group()))
                            .append("</code>");
                    i = reference.end() - 1;
                    continue;
                }
            }
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> {
                    boolean opens = ALWAYS_ESCAPED.indexOf(c) >= 0
                            // `www.example.com` and `a@b.co` would become links.
                            || (c == '.' && i + 1 < text.length() && isAsciiAlphanumeric(text.charAt(i + 1)))
                            // A heading, a list, a quote, a rule, a setext underline or a quick action.
                            || (lineStart && i == 0 && isAsciiPunctuation(c))
                            // An ordered list: `1.` or `1)`.
                            || (i == leadingDigits && i > 0 && (c == '.' || c == ')'));
                    if (opens) out.append('\\');
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static String punctuationEscaped(String name) {
        StringBuilder out = new StringBuilder(name.length() * 2);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (isAsciiPunctuation(c)) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isAsciiDigit(c);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
