package de.tum.cit.aet.hephaestus.evidence;

/**
 * Captured provider text as a model reads it: each hidden character marked ({@link HiddenCharacters}). The staged
 * evidence and the fingerprint of a source both read it this way, so marking never makes a capture look changed.
 * Copies of repository files under {@code context/citable/} keep their bytes, so a quote of code still matches the
 * checkout and the diff that the model reads.
 */
public final class CapturedTextMarking {

    private static final String REPOSITORY_COPIES = "context/citable/";

    private CapturedTextMarking() {}

    public static byte[] shown(String path, byte[] bytes) {
        return path.startsWith(REPOSITORY_COPIES) ? bytes : HiddenCharacters.markUtf8(bytes);
    }
}
