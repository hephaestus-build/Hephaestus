package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How a person recognises one artifact: the number they typed, the title they read, where it lives.
 *
 * <p>The signal ledger stores nothing but {@code (kind, id)}, so this is the one thing core asks a
 * domain module to translate for display.
 *
 * <p>Deliberately not the same type as {@code ReviewRunLookup.Target}, which decodes a job's own
 * metadata and so cannot answer for an artifact no job ran on.
 *
 * @param provider  the integration the artifact lives at, which decides its noun and how its number is
 *                  written: a GitLab {@code scm.pull_request} is merge request {@code !12}
 * @param number    the number the provider shows, when the kind has one; a document does not
 * @param title     a human label; never blank, so a caller never has to invent one
 * @param container where it sits, by the name a person reads: a repository, a collection, a channel
 * @param url       where to open it upstream, when we can build a whole one
 */
public record ArtifactIdentity(
        @NonNull ArtifactKind kind,
        @NonNull Long id,
        @Nullable IntegrationKind provider,
        @Nullable Integer number,
        @NonNull String title,
        @Nullable String container,
        @Nullable String url) {
    public ArtifactIdentity {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("An artifact identity needs a title; use the kind's display name");
        }
    }

    /** The identity of something we can name only by its kind — the honest answer, not a missing one. */
    public static ArtifactIdentity unresolved(ArtifactKind kind, Long id, String kindDisplayName) {
        return new ArtifactIdentity(kind, id, null, null, kindDisplayName, null, null);
    }
}
