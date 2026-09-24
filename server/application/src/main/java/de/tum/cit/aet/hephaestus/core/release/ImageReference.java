package de.tum.cit.aet.hephaestus.core.release;

import java.util.regex.Pattern;

/** The one grammar for an image reference a release lock pins: a lowercase name and a sha256 digest, never a tag. */
public final class ImageReference {
    public static final String DIGEST_PINNED = "[a-z0-9][a-z0-9._/:-]*@sha256:[a-f0-9]{64}";

    private static final Pattern DIGEST_PINNED_PATTERN = Pattern.compile(DIGEST_PINNED);

    private ImageReference() {}

    public static boolean isDigestPinned(String reference) {
        return DIGEST_PINNED_PATTERN.matcher(reference).matches();
    }
}
