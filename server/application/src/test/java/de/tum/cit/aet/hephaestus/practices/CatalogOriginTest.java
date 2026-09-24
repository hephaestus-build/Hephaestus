package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.curated.CatalogEntry;
import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogLink;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogOriginDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CatalogOriginTest extends BaseUnitTest {

    private static final String SLUG = "small-prs";

    @Test
    void aPracticeTheWorkspaceWroteItselfHasNoOrigin() {
        assertThat(CatalogOrigin.of(practice("Seed criteria", null), catalog("Seed criteria")))
                .isNull();
    }

    @Test
    void anUntouchedCopyOfWhatTheCatalogOffersIsInSync() {
        Practice copy = practice("Seed criteria", fingerprintOf("Seed criteria"));

        assertThat(origin(copy, catalog("Seed criteria")).link()).isEqualTo(CatalogLink.IN_SYNC);
    }

    @Test
    void aCopyTheWorkspaceChangedReadsAsEditedHere() {
        Practice copy = practice("Our own criteria", fingerprintOf("Seed criteria"));

        assertThat(origin(copy, catalog("Seed criteria")).link()).isEqualTo(CatalogLink.LOCALLY_EDITED);
    }

    @Test
    void anUntouchedCopyWhoseCatalogEntryMovedOnReadsAsUpdateAvailable() {
        Practice copy = practice("Seed criteria", fingerprintOf("Seed criteria"));

        assertThat(origin(copy, catalog("Instance criteria")).link()).isEqualTo(CatalogLink.UPDATE_AVAILABLE);
    }

    @Test
    void aDeclinedOfferDoesNotStillReadAsAvailable() {
        Practice copy = practice("Seed criteria", fingerprintOf("Seed criteria"));
        copy.setAdoptedBase(definition("Seed criteria"));
        copy.setAdoptedBaseSource(AdoptedBaseSource.EXACT_ADOPTION);
        copy.setDeclinedOfferedDigest(definition("Instance criteria").exactFingerprint(SLUG));

        assertThat(origin(copy, catalog("Instance criteria")).link()).isEqualTo(CatalogLink.DECLINED);
        assertThat(origin(copy, catalog("Later criteria")).link()).isEqualTo(CatalogLink.UPDATE_AVAILABLE);
    }

    @Test
    void aCopyEditedAwayAndBackIsInSyncAgain() {
        Practice copy = practice("Our own criteria", fingerprintOf("Seed criteria"));
        assertThat(origin(copy, catalog("Seed criteria")).link()).isEqualTo(CatalogLink.LOCALLY_EDITED);

        copy = practice("Seed criteria", fingerprintOf("Seed criteria"));

        assertThat(origin(copy, catalog("Seed criteria")).link()).isEqualTo(CatalogLink.IN_SYNC);
    }

    @Test
    void aCopyWhoseCatalogEntryIsNoLongerOfferedSaysSoWithoutChanging() {
        Practice copy = practice("Seed criteria", fingerprintOf("Seed criteria"));
        EffectiveCatalog retired = new EffectiveCatalog(
                List.of(),
                List.of(new CatalogEntry<>(
                        SLUG, definition("Seed criteria"), definition("Seed criteria"), null, null, true, 0, null)));

        var origin = CatalogOrigin.of(copy, retired);
        assertNotNull(origin);
        assertThat(origin.link()).isEqualTo(CatalogLink.IN_SYNC);
        assertThat(origin.sourceOffered()).isFalse();
    }

    @Test
    void aPracticeUnderARetiredGroupIsNoLongerOffered() {
        Practice copy = practice("Seed criteria", fingerprintOf("Seed criteria"));
        GroupDefinition group = new GroupDefinition("Quality", null, null, null);
        PracticeDefinition practice = new PracticeDefinition(
                "Small PRs",
                PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                "Seed criteria",
                null,
                PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                "Reason",
                null,
                "quality");
        EffectiveCatalog catalog = new EffectiveCatalog(
                List.of(new CatalogEntry<>("quality", group, group, null, null, true, 0, null)),
                List.of(CatalogEntry.shippedOnly(SLUG, practice, 0)));

        assertThat(origin(copy, catalog).sourceOffered()).isFalse();
    }

    private static PracticeDefinition definition(String criteria) {
        return new PracticeDefinition(
                "Small PRs",
                PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                criteria,
                null,
                PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                "Reason",
                null,
                null);
    }

    private static String fingerprintOf(String criteria) {
        return definition(criteria).provenanceFingerprint(SLUG);
    }

    private static EffectiveCatalog catalog(String criteria) {
        return new EffectiveCatalog(List.of(), List.of(CatalogEntry.shippedOnly(SLUG, definition(criteria), 0)));
    }

    private static Practice practice(String criteria, @Nullable String copiedFromFingerprint) {
        Practice practice = new Practice();
        practice.setId(1L);
        practice.setSlug(SLUG);
        practice.setName("Small PRs");
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria(criteria);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setWhyItMatters("Reason");
        if (copiedFromFingerprint != null) {
            practice.setSourceCuratedSlug(SLUG);
            practice.setSourceCuratedFingerprint(copiedFromFingerprint);
        }
        practice.setCurrentRevision(new PracticeRevision(practice, 1));
        return practice;
    }

    private static CatalogOriginDTO origin(Practice practice, EffectiveCatalog catalog) {
        CatalogOriginDTO origin = CatalogOrigin.of(practice, catalog);
        assertNotNull(origin);
        return origin;
    }
}
