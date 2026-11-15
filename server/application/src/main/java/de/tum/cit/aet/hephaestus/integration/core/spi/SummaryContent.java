package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A summary to post. {@code body} is untrusted text, which the writer escapes. {@code header} is Markdown the bot built
 * from known data, such as a link to the file a note is about, and is posted as it is before the text.
 * {@code provenance} is the signed record of {@code body}, which the writer posts as a hidden line; {@code null} when
 * the text is not generated, such as the words of a withdrawal.
 */
public record SummaryContent(
        UUID copyId,
        String body,
        String writeKey,
        @Nullable String writeMarker,
        @Nullable String header,
        @Nullable String provenance) {
    public SummaryContent {

        if (writeKey.isBlank()) throw new IllegalArgumentException("Summary write key is blank");
    }

    public SummaryContent(UUID copyId, String body, String writeKey, @Nullable String writeMarker) {
        this(copyId, body, writeKey, writeMarker, null, null);
    }

    public SummaryContent(
            UUID copyId, String body, String writeKey, @Nullable String writeMarker, @Nullable String header) {
        this(copyId, body, writeKey, writeMarker, header, null);
    }
}
