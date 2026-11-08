package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;

/** Reads actual provider text and placement without changing a copy or a person's thread state. */
public interface FeedbackCopyReader {
    IntegrationKind kind();

    List<CopyReadback> read(FeedbackTarget target, List<CopyReadRequest> copies);
}
