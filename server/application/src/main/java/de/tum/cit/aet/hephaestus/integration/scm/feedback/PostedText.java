package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import java.util.ArrayList;
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
 * <p>Each run of non-blank characters that a provider could link becomes a code span: a name, an e-mail address, an
 * issue or merge request reference, a web address, a commit, or link syntax. Neither provider links anything inside
 * code, whatever stands next to it. In the rest, {@code &}, {@code <} and {@code >} become character references, and
 * a backslash goes before each of {@code \ ` * _ ~ [ ] | $ { }} and before the punctuation that starts a line or ends
 * the number at its start, so no line becomes a block. A character reference is never decoded: it is posted as the
 * characters it is written with.
 *
 * <p>It runs once on each untrusted text; every write and every readback comparison calls it with the same input.
 */
public final class PostedText {

    private PostedText() {}

    // What a provider links in a run: a name or an e-mail address; an issue, pull request, merge request, epic,
    // snippet, label, milestone or iteration reference; a GitLab project path before `>`; a web address; link, image
    // or footnote syntax; a custom emoji, which renders as an image; a GitLab inline diff mark; a commit, which both
    // providers find in any 7 to 64 hexadecimal characters. GitHub and GitLab read link syntax and diff marks again
    // in the rendered text, through any escape, so they are code too.
    private static final Pattern LINKABLE = Pattern.compile("@|#[A-Za-z0-9_\"]|[~%].|[!&$]\\d|GH-\\d"
            + "|(?i:www\\.|\\*iteration:)|://|[\\[\\]]|[A-Za-z0-9_./-](?:>|&gt;)|:[A-Za-z0-9_+-]+:"
            + "|\\{[+-]|[+-]\\}"
            + "|(?<![A-Za-z0-9_])[0-9A-Fa-f]{7,64}(?![A-Za-z0-9_])");

    // `[text](address)` and `[text][label]`, whose text may hold spaces, are one code span.
    private static final Pattern LINK_SYNTAX =
            Pattern.compile("!?\\[[^\\[\\]]*\\]\\s*(?:\\([^()]*\\)|\\[[^\\[\\]]*\\])");

    // Punctuation around a run that stays outside its code span.
    private static final String OPENING = "(\"'";
    private static final String CLOSING = ".,;!?)'\"";

    // Emphasis, code, escapes, strikethrough, tables and GitLab math. GitLab's diff marks survive an escaped brace, so
    // a run with one is code. Other punctuation opens nothing inside a line.
    private static final String ALWAYS_ESCAPED = "\\`*_~[]|${}";

    // The parser reads this in place of `&`, so it decodes no character reference.
    private static final char AMPERSAND = '￿';

    // GitHub takes at most 65,536 characters in a comment and GitLab more; the escaped text leaves room for the header
    // and the hidden markers.
    static final int MAX_POSTED_TEXT = 60_000;

    static final String SHORTENED = "\n\n(Shortened to fit the comment length limit.)";

    // GitLab's quick action reader drops a lone carriage return, so every line end is one before anything else.
    private static final Pattern LINE_END = Pattern.compile("\\r\\n?|[\\u2028\\u2029\\u0085]");

    // Direction controls, zero-width characters and tag characters make the text read differently from its bytes. Both
    // providers drop a byte order mark that starts the text, so the line after it would open a block. U+FFFF is no
    // character; the parser reads it in place of `&`.
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\uFFFF\\x{E0000}-\\x{E007F}]");

    // Each line is read alone and with no block parser, so only its inline Markdown has a meaning.
    private static final Parser INLINE = Parser.builder()
            .enabledBlockTypes(Set.of())
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /** One line of untrusted text, as literal text: its line ends become spaces. */
    public static String plain(String untrusted) {
        StringBuilder out = new StringBuilder();
        for (Piece piece : pieces(untrusted, true)) out.append(piece.code() ? codeSpan(piece.text()) : piece.text());
        return out.toString();
    }

    /** Untrusted text shown as code: one code span, which no backtick inside it can end. */
    public static String inlineCode(String untrusted) {
        String text = normalized(untrusted).replace('\n', ' ');
        return text.isEmpty() ? "" : codeSpan(text);
    }

    /**
     * Untrusted text of several lines, with its code spans and emphasis, that keeps its line breaks. Leading spaces
     * are dropped, so no line becomes code.
     */
    public static String of(String untrusted) {
        String text = normalized(untrusted);
        String posted = lines(text);
        if (posted.length() <= MAX_POSTED_TEXT) return posted;
        // A cut changes how the text before it reads, such as a code span that loses its closing backticks, so the
        // posted length does not grow with the cut. The search keeps only a cut whose posted form it measured to fit;
        // the empty cut always fits.
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (shortened(text, middle).length() <= MAX_POSTED_TEXT) low = middle;
            else high = middle - 1;
        }
        return shortened(text, low);
    }

    /** The text cut at the last space before {@code length}, posted, with the note that it was shortened. */
    private static String shortened(String text, int length) {
        int cut = Math.max(text.lastIndexOf(' ', length), text.lastIndexOf('\n', length));
        if (cut <= 0) cut = length;
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
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
            String line = lines[i].strip().replace('&', AMPERSAND);
            if (!line.isEmpty()) new Line(line, out).children(INLINE.parse(line));
        }
        return out.toString().strip();
    }

    private static String normalized(String text) {
        return INVISIBLE.matcher(LINE_END.matcher(text).replaceAll("\n")).replaceAll("");
    }

    /** Writes one parsed line: its code spans and emphasis again, and the rest of it as literal text. */
    private static final class Line {
        private final String source;
        private final StringBuilder out;
        private final StringBuilder text = new StringBuilder();
        private String code = "";
        private int codeStart = -1;
        private int codeEnd = -1;

        Line(String source, StringBuilder out) {
            this.source = source;
            this.out = out;
        }

        void children(Node parent) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                switch (node) {
                    case Document document -> children(document);
                    case Paragraph paragraph -> children(paragraph);
                    case Text words -> text.append(words.getLiteral());
                    case Code span -> {
                        flush();
                        code(span.getLiteral().replace(AMPERSAND, '&'));
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
            int start = out.length();
            out.append(delimiter);
            children(node);
            String content = out.substring(start + delimiter.length());
            if (content.isBlank()) {
                // Delimiters around nothing visible would read as a rule or a list marker.
                String literal = "\\*".repeat(delimiter.length());
                out.setLength(start);
                out.append(literal).append(content).append(literal);
            } else {
                out.append(delimiter);
            }
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
            for (Piece piece : pieces(text.toString(), lineStart)) {
                if (piece.code()) code(piece.text());
                else out.append(piece.text());
            }
            text.setLength(0);
        }

        /** A code span; one right after another joins it, because two backtick runs side by side are one run. */
        private void code(String content) {
            boolean joined = out.length() == codeEnd;
            if (joined) out.setLength(codeStart);
            code = joined ? code + content : content;
            codeStart = out.length();
            out.append(codeSpan(code));
            codeEnd = out.length();
        }
    }

    /** Literal text, or the text of a code span. */
    private record Piece(boolean code, String text) {}

    /** Text that renders as itself: each run that a provider could link is code, the rest is escaped. */
    private static List<Piece> pieces(String untrusted, boolean lineStart) {
        String text = normalized(untrusted.replace(AMPERSAND, '&')).replace('\n', ' ');
        if (lineStart) text = text.stripLeading();
        int leadingDigits = 0;
        while (lineStart && leadingDigits < text.length() && isAsciiDigit(text.charAt(leadingDigits))) leadingDigits++;
        var pieces = new ArrayList<Piece>();
        var literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                literal.append(text.charAt(i++));
                continue;
            }
            Matcher link = LINK_SYNTAX.matcher(text).region(i, text.length());
            int from = i;
            int to;
            int end;
            if (link.lookingAt()) {
                end = link.end();
                to = end;
            } else {
                end = i;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
                // The whole run decides, so `50%.` counts although its code span ends before the dot.
                boolean linkable = LINKABLE.matcher(text.substring(i, end)).find();
                while (from < end && OPENING.indexOf(text.charAt(from)) >= 0) from++;
                to = end;
                while (to > from && CLOSING.indexOf(text.charAt(to - 1)) >= 0) to--;
                if (!linkable) {
                    from = end;
                    to = end;
                }
            }
            escape(text, i, from, lineStart, leadingDigits, literal);
            if (from < to) {
                if (!literal.isEmpty()) pieces.add(new Piece(false, literal.toString()));
                literal.setLength(0);
                String code = text.substring(from, to);
                if (!pieces.isEmpty() && pieces.getLast().code())
                    code = pieces.removeLast().text() + code;
                pieces.add(new Piece(true, code));
            }
            escape(text, to, end, lineStart, leadingDigits, literal);
            i = end;
        }
        if (!literal.isEmpty()) pieces.add(new Piece(false, literal.toString()));
        return pieces;
    }

    private static void escape(String text, int from, int to, boolean lineStart, int leadingDigits, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> {
                    boolean opens = ALWAYS_ESCAPED.indexOf(c) >= 0
                            // A heading, a list, a quote, a rule, a setext underline or a quick action.
                            || (lineStart && i == 0 && isAsciiPunctuation(c))
                            // An ordered list: `1.` or `1)`.
                            || (lineStart && i == leadingDigits && i > 0 && (c == '.' || c == ')'));
                    if (opens) out.append('\\');
                    out.append(c);
                }
            }
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

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
