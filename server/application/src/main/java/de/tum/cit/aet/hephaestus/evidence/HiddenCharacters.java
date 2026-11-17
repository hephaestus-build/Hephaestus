package de.tum.cit.aet.hephaestus.evidence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Makes characters that a person cannot see visible before provider text reaches a model (OWASP LLM01). Tag
 * characters, bidirectional controls, zero-width characters and other default-ignorable or control characters can
 * hide an instruction in a pull request, a Slack message or a document that a reviewer never sees. Each one becomes
 * {@code ⟦U+XXXX⟧}, so the model and anyone who reads its quote see that it is there. Tab, line feed and carriage
 * return stay. A single zero-width joiner or non-joiner between two visible non-ASCII characters stays too: emoji
 * sequences, Persian and Indic words need it (RFC 5892 CONTEXTJ). Next to ASCII it is marked, so a JSON record, whose
 * escapes and quotes are ASCII, and the string extracted from it decide the same way.
 *
 * <p>Applied where captured provider text enters a job's evidence ({@code JobFolderIndexBuilder}) and a
 * conversation's context ({@code WorkspaceContextBuilder}). On output, {@code FeedbackTextPolicy} refuses the same
 * characters and {@code PostedText} strips them.
 */
public final class HiddenCharacters {

    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private HiddenCharacters() {}

    /** Whether {@code text} has a character that {@link #mark} writes as {@code ⟦U+XXXX⟧}. */
    public static boolean hides(String text) {
        int[] codePoints = text.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) if (hiddenAt(codePoints, index)) return true;
        return false;
    }

    /** The text with each hidden character written as {@code ⟦U+XXXX⟧}; the same instance when it has none. */
    public static String mark(String text) {
        if (!hides(text)) return text;
        int[] codePoints = text.codePoints().toArray();
        var out = new StringBuilder(text.length() + 16);
        for (int index = 0; index < codePoints.length; index++) {
            if (hiddenAt(codePoints, index)) out.append(String.format(Locale.ROOT, "⟦U+%04X⟧", codePoints[index]));
            else out.appendCodePoint(codePoints[index]);
        }
        return out.toString();
    }

    /**
     * UTF-8 text with each hidden character marked; the same array when it has none or is not UTF-8 text, so binary
     * content passes as it is.
     */
    public static byte[] markUtf8(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException binary) {
            return bytes;
        }
        return hides(text) ? mark(text).getBytes(StandardCharsets.UTF_8) : bytes;
    }

    private static boolean hiddenAt(int[] codePoints, int index) {
        int codePoint = codePoints[index];
        if (codePoint == ZERO_WIDTH_JOINER || codePoint == ZERO_WIDTH_NON_JOINER)
            return !(visible(codePoints, index - 1) && visible(codePoints, index + 1));
        return hidden(codePoint);
    }

    private static boolean visible(int[] codePoints, int index) {
        if (index < 0 || index >= codePoints.length) return false;
        int codePoint = codePoints[index];
        return codePoint > 0x7F
                && !hidden(codePoint)
                && codePoint != ZERO_WIDTH_JOINER
                && codePoint != ZERO_WIDTH_NON_JOINER
                && !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint);
    }

    /** Whether a person cannot see {@code codePoint} in rendered text; the joiners depend on their neighbours. */
    private static boolean hidden(int codePoint) {
        // Prepended concatenation marks (Arabic, Syriac, Kaithi) are format characters that render visibly.
        if ((codePoint >= 0x0600 && codePoint <= 0x0605)
                || codePoint == 0x06DD
                || codePoint == 0x070F
                || codePoint == 0x0890
                || codePoint == 0x0891
                || codePoint == 0x08E2
                || codePoint == 0x110BD
                || codePoint == 0x110CD) return false;
        // Variation selectors can carry a hidden payload; the text and emoji selectors (FE0E, FE0F) only style.
        if ((codePoint >= 0xFE00 && codePoint <= 0xFE0D) || (codePoint >= 0xE0100 && codePoint <= 0xE01EF)) return true;
        // Default-ignorable code points outside the format category (UAX #44), and characters that render blank.
        if (codePoint == 0x034F
                || codePoint == 0x115F
                || codePoint == 0x1160
                || codePoint == 0x17B4
                || codePoint == 0x17B5
                || (codePoint >= 0x180B && codePoint <= 0x180F)
                || codePoint == 0x2065
                || codePoint == 0x2800
                || codePoint == 0x3164
                || codePoint == 0xFFA0
                || (codePoint >= 0xFFF0 && codePoint <= 0xFFF8)
                || (codePoint >= 0xE0000 && codePoint <= 0xE0FFF)) return true;
        return switch (Character.getType(codePoint)) {
            case Character.FORMAT -> true;
            case Character.CONTROL -> codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }
}
