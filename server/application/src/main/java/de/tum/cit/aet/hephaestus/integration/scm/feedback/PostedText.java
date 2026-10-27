package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Untrusted text as Hephaestus posts it on the work: model text, quoted work, practice guidance and repository paths.
 * It keeps no Markdown structure, because GitHub and GitLab read Markdown in dialects that no parser here matches.
 * Every ASCII punctuation mark is escaped with a backslash (CommonMark lets any of them be escaped, and both
 * providers follow it), {@code &}, {@code <} and {@code >} become character references, and every name a provider
 * links and every issue or merge request reference becomes code: {@code <code>@name</code>}. A character reference
 * or a backslash before the at sign or the number sign is no escape: the providers link through them. The result
 * renders as the literal text on both providers; nothing in it opens a fence, a quote, a list, a footnote, a link, a
 * table or a GitLab quick action. Only the bot's own template and markers hold structure.
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

    // GitLab's quick action reader drops a lone carriage return, so every line end is one before anything else.
    private static final Pattern LINE_END = Pattern.compile("\\r\\n?|[\\u2028\\u2029\\u0085]");

    // Direction controls, zero-width characters and tag characters make the text read differently from its bytes.
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\x{E0000}-\\x{E007F}]");

    /** One line of untrusted text, as literal text: its line ends become spaces. */
    public static String plain(String untrusted) {
        return escaped(normalized(untrusted).replace('\n', ' '));
    }

    /** Untrusted text shown as code: an HTML code element around the literal text, which no backtick can end. */
    public static String inlineCode(String untrusted) {
        return "<code>" + plain(untrusted) + "</code>";
    }

    /**
     * Untrusted text of several lines, as literal text that keeps its line breaks. Leading spaces are dropped, so no
     * line becomes code.
     */
    public static String of(String untrusted) {
        String[] lines = normalized(untrusted).split("\n", -1);
        StringBuilder out = new StringBuilder(untrusted.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(escaped(lines[i].strip()));
        }
        return out.toString().strip();
    }

    /** A summary as it is posted: its text made literal, then the hidden marker that finds it again. */
    public static String summary(SummaryContent content) {
        String marker = content.writeMarker();
        if (marker == null || marker.isBlank()) return of(content.body());
        return of(content.body().replace(marker, "")) + "\n\n" + marker;
    }

    private static String normalized(String text) {
        return INVISIBLE.matcher(LINE_END.matcher(text).replaceAll("\n")).replaceAll("");
    }

    private static String escaped(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
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
                    if (isAsciiPunctuation(c)) out.append('\\');
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
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
