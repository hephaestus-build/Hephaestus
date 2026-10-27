package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import org.jspecify.annotations.Nullable;

/** @param pattern what a {@link ReadReceiptStatus#SEARCHED} search looked for; null on every other receipt */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadReceipt(
        SourceKind sourceKind,
        String sourcePath,
        String path,
        String version,
        ReadReceiptStatus status,
        @Nullable String contentDigest,
        @Nullable Integer startLine,
        @Nullable Integer endLine,
        @Nullable String pattern) {}
