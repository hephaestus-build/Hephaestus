package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What the provider shows for one retained copy, after its item, author and marker were checked. */
public record CopyReadback(
        UUID copyId,
        Status status,
        @Nullable String body,
        @Nullable String renderedBody,
        @Nullable FeedbackAnchor anchor,
        boolean resolved,
        boolean outdated) {
    public enum Status {
        FOUND,
        GONE,
        NOT_OWNED,
        HUMAN_EDITED,
        UNAVAILABLE
    }

    public CopyReadback {
        Objects.requireNonNull(copyId);
        Objects.requireNonNull(status);
        if (status == Status.FOUND && (body == null || renderedBody == null))
            throw new IllegalArgumentException("A readback needs the provider's text and rendering");
        if (status != Status.FOUND && (body != null || renderedBody != null || anchor != null))
            throw new IllegalArgumentException("An unverified copy supplies no content");
    }

    public static CopyReadback refused(UUID copyId, Status status) {
        if (status == Status.FOUND) throw new IllegalArgumentException("A found copy needs its content");
        return new CopyReadback(copyId, status, null, null, null, false, false);
    }
}
