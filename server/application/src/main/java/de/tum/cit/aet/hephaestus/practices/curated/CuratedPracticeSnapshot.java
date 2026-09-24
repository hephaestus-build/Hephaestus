package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicyDigest;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import java.util.List;
import org.jspecify.annotations.Nullable;

record CuratedPracticeSnapshot(
        String slug,
        CatalogEntryState state,
        boolean offered,
        int position,
        String name,
        ArtifactKind artifactKind,
        List<PracticeBinding> bindings,
        String criteriaSha256,
        @Nullable String precomputeScriptSha256,
        String automatedReviewPolicySha256,
        @Nullable String whyItMatters,
        @Nullable String whatGoodLooksLike,
        @Nullable String groupSlug,
        @Nullable String shippedDigest,
        PracticeDeliveryBehavior deliveryBehavior)
        implements ConfigAuditSnapshot {
    static CuratedPracticeSnapshot of(CatalogEntry<PracticeDefinition> entry) {
        PracticeDefinition definition = entry.effective();
        return new CuratedPracticeSnapshot(
                entry.slug(),
                entry.state(),
                entry.offered(),
                entry.position(),
                definition.name(),
                definition.artifactKind(),
                definition.bindings(),
                CanonicalDigest.sha256Hex(definition.criteria()),
                definition.precomputeScript() == null ? null : CanonicalDigest.sha256Hex(definition.precomputeScript()),
                PracticeAutomatedReviewPolicyDigest.digest(definition.automatedReviewPolicy()),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug(),
                entry.shipped() == null ? null : entry.shipped().digest(entry.slug()),
                definition.deliveryBehavior());
    }
}
