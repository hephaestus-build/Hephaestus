package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.List;
import org.jspecify.annotations.Nullable;

record PracticeDefinitionSnapshot(
        String slug,
        String name,
        ArtifactKind artifactKind,
        List<PracticeBinding> bindings,
        @Nullable Integer criteriaRevision,
        String criteriaSha256,
        @Nullable String precomputeScriptSha256,
        String automatedReviewPolicySha256,
        @Nullable String whyItMatters,
        @Nullable String whatGoodLooksLike,
        @Nullable String groupSlug,
        @Nullable String sourceCuratedSlug,
        @Nullable String sourceCuratedFingerprint,
        @Nullable AdoptedBaseSource adoptedBaseSource)
        implements ConfigAuditSnapshot {
    static PracticeDefinitionSnapshot of(Practice practice, @Nullable Integer criteriaRevision) {
        return new PracticeDefinitionSnapshot(
                practice.getSlug(),
                practice.getName(),
                practice.getArtifactKind(),
                practice.getBindings(),
                criteriaRevision,
                CanonicalDigest.sha256Hex(practice.getCriteria()),
                practice.getPrecomputeScript() == null
                        ? null
                        : CanonicalDigest.sha256Hex(practice.getPrecomputeScript()),
                PracticeAutomatedReviewPolicyDigest.digest(practice.getAutomatedReviewPolicy()),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                practice.getGroup() == null ? null : practice.getGroup().getSlug(),
                practice.getSourceCuratedSlug(),
                practice.getSourceCuratedFingerprint(),
                practice.getAdoptedBaseSource());
    }
}
