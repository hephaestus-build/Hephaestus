package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A summary to post. {@code body} is untrusted text, which the writer escapes. {@code header} is Markdown the bot built
 * from known data, such as a link to the file a note is about, and is posted as it is before the text.
 */
public record SummaryContent(
        UUID copyId,
        String body,
        String writeKey,
        @Nullable String writeMarker,
        @Nullable String header) {
    public SummaryContent {

        if (writeKey.isBlank()) throw new IllegalArgumentException("Summary write key is blank");
    }

    public SummaryContent(UUID copyId, String body, String writeKey, @Nullable String writeMarker) {
        this(copyId, body, writeKey, writeMarker, null);
    }
}
