package de.tum.cit.aet.hephaestus.evidence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Makes characters that a person cannot see visible before provider text reaches a model (OWASP LLM01). Tag
 * characters, bidirectional controls, zero-width characters and other format or control characters can hide an
 * instruction in a pull request, a Slack message or a document that a reviewer never sees. Each one becomes
 * {@code ⟦U+XXXX⟧}, so the model and anyone who reads its quote see that it is there. A zero-width joiner, which
 * composes emoji, and the tab, line feed and carriage return stay.
 *
 * <p>Applied where captured text enters a job's evidence ({@code JobFolderIndexBuilder}) and a conversation's
 * context ({@code WorkspaceContextBuilder}), so a quote and its check read the same marked text. Repository files
 * keep their bytes. On output, {@code FeedbackTextPolicy} and {@code PostedText} refuse or strip these characters.
 */
public final class HiddenCharacters {

    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private HiddenCharacters() {}

    /** Whether a person cannot see {@code codePoint} in rendered text. */
    public static boolean hidden(int codePoint) {
        // Variation selectors can carry a hidden payload; the text and emoji selectors (FE0E, FE0F) only style.
        if ((codePoint >= 0xFE00 && codePoint <= 0xFE0D) || (codePoint >= 0xE0100 && codePoint <= 0xE01EF)) return true;
        // Fillers and the grapheme joiner render as nothing.
        if (codePoint == 0x034F
                || codePoint == 0x115F
                || codePoint == 0x1160
                || codePoint == 0x3164
                || codePoint == 0xFFA0) return true;
        return switch (Character.getType(codePoint)) {
            case Character.FORMAT -> codePoint != ZERO_WIDTH_JOINER;
            case Character.CONTROL -> codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }

    /** The text with each hidden character written as {@code ⟦U+XXXX⟧}; the same instance when it has none. */
    public static String mark(String text) {
        if (text.codePoints().noneMatch(HiddenCharacters::hidden)) return text;
        var out = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(codePoint -> {
            if (hidden(codePoint)) out.append(String.format(Locale.ROOT, "⟦U+%04X⟧", codePoint));
            else out.appendCodePoint(codePoint);
        });
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
        if (text.codePoints().noneMatch(HiddenCharacters::hidden)) return bytes;
        return mark(text).getBytes(StandardCharsets.UTF_8);
    }
}
