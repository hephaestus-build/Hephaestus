package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.AdoptedBaseSource;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionMerge;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One offer and the exact three versions an administrator reviews before deciding. */
public record PracticeReleaseProposalDTO(
        @NonNull String slug,
        @NonNull PracticeDefinition base,
        @NonNull PracticeDefinition current,
        @NonNull PracticeDefinition offered,
        @NonNull AdoptedBaseSource baseSource,
        @NonNull String offeredDigest,
        @NonNull String etag,
        @Nullable Integer currentRevision,
        @NonNull List<PracticeReleaseFieldDTO> fields) {
    public static PracticeReleaseProposalDTO of(
            String slug,
            PracticeDefinition base,
            PracticeDefinition current,
            PracticeDefinition offered,
            AdoptedBaseSource baseSource,
            @Nullable Integer currentRevision) {
        String offeredDigest = offered.exactFingerprint(slug);
        String etag = new CanonicalDigest()
                .add(slug)
                .add(base.exactFingerprint(slug))
                .add(current.exactFingerprint(slug))
                .add(offeredDigest)
                .addInt(currentRevision == null ? 0 : currentRevision)
                .hex();
        return new PracticeReleaseProposalDTO(
                slug,
                base,
                current,
                offered,
                baseSource,
                offeredDigest,
                etag,
                currentRevision,
                PracticeDefinitionMerge.changes(base, current, offered).stream()
                        .map(PracticeReleaseFieldDTO::from)
                        .toList());
    }
}
