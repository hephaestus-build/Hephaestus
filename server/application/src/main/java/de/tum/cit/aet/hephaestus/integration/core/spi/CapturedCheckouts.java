package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Copies of a checkout that a capture recorded, possibly on another server. This server builds the checkout from its
 * own mirror, fetches the commit from the provider when the mirror lacks it, and keeps the built checkout in its cache
 * beside the mirror.
 */
public interface CapturedCheckouts {
    /**
     * Copies the checkout of {@code key} to {@code target}, which must not exist. The copy's commit has the recorded
     * tree, and {@code verified} accepts the copy: the caller's check of the recorded checkout digest.
     *
     * @throws CheckoutUnverifiedException when no checkout with that identity can be built here; no copy remains
     */
    void copy(long workspaceId, CheckoutKey key, Path target, Predicate<Path> verified);
}
