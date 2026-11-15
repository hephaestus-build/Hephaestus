package de.tum.cit.aet.hephaestus.core.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The form of a text that a provenance record hashes. Line ends, trailing spaces and Unicode composition differ between
 * the database, a provider's API and a clipboard without changing the words, so the hash ignores them.
 */
public final class ProvenanceText {

    private static final Pattern LINE_END = Pattern.compile("\\r\\n?");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);

    private ProvenanceText() {}

    /** NFC, LF line ends, no trailing spaces or tabs on any line, no leading or trailing blank space. */
    public static String canonical(String text) {
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return TRAILING_SPACE
                .matcher(LINE_END.matcher(nfc).replaceAll("\n"))
                .replaceAll("")
                .strip();
    }

    /** SHA-256 of the canonical form's UTF-8 bytes. */
    public static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical(text).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
