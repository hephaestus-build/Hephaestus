package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import de.tum.cit.aet.hephaestus.practices.PracticeReleaseChoice;
import de.tum.cit.aet.hephaestus.practices.StalePracticeReleaseException;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("integration")
class CuratedPracticeReleaseIntegrationTest extends BaseIntegrationTest {

    private static final String SLUG = "describe-what-and-why";

    @Autowired
    private CuratedCatalogService catalog;

    @Autowired
    private CuratedPracticeReleaseService releases;

    @Autowired
    private CuratedPracticeOverrideRepository overrides;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void acceptCanKeepAConflictingFieldAndAdvanceTheBundledBase() {
        customizeFromOlderBundle();
        PracticeReleaseProposalDTO proposal = releases.practiceRelease(SLUG);
        assertThat(proposal.fields()).anySatisfy(field -> {
            assertThat(field.field()).isEqualTo(PracticeDefinitionField.CRITERIA);
            assertThat(field.conflict()).isTrue();
        });
        var entry = catalog.practice(SLUG);
        assertThatThrownBy(() -> catalog.keepPractice(SLUG, EntityTagPrecondition.parse("\"" + entry.etag() + "\"")))
                .isInstanceOf(CuratedCatalogConflictException.class);
        assertThatThrownBy(() -> releases.accept(
                        SLUG,
                        EntityTagPrecondition.parse("\"stale\""),
                        Map.of(PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.CURRENT)))
                .isInstanceOf(StalePracticeReleaseException.class);

        releases.accept(SLUG, match(proposal), Map.of(PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.CURRENT));

        CuratedPracticeOverride saved = overrides.findBySlug(SLUG).orElseThrow();
        assertThat(Objects.requireNonNull(saved.definition()).criteria()).isEqualTo("Local criteria");
        assertThat(saved.getAdoptedBase()).isEqualTo(proposal.offered());
        assertThat(saved.getAcceptedBundledDigest()).isEqualTo(CuratedDefinitionDigest.of(SLUG, proposal.offered()));
        assertThat(catalog.practice(SLUG).state()).isEqualTo(CatalogEntryState.EDITED_HERE);
        assertThatThrownBy(() -> releases.practiceRelease(SLUG)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void declineAcknowledgesOnlyThisOfferWithoutChangingTheBaseOrDefinition() {
        customizeFromOlderBundle();
        PracticeReleaseProposalDTO proposal = releases.practiceRelease(SLUG);

        releases.decline(SLUG, match(proposal));

        CuratedPracticeOverride saved = overrides.findBySlug(SLUG).orElseThrow();
        assertThat(saved.definition()).isEqualTo(proposal.current());
        assertThat(saved.getAdoptedBase()).isEqualTo(proposal.base());
        assertThat(saved.getAcceptedBundledDigest()).isEqualTo(CuratedDefinitionDigest.of(SLUG, proposal.offered()));
        assertThat(catalog.practice(SLUG).state()).isEqualTo(CatalogEntryState.EDITED_HERE);
        assertThatThrownBy(() -> releases.practiceRelease(SLUG)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void acceptingTheOfferedDefinitionReturnsToFollowingTheBundle() {
        customizeFromOlderBundle();
        PracticeReleaseProposalDTO proposal = releases.practiceRelease(SLUG);

        releases.accept(SLUG, match(proposal), Map.of(PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.OFFERED));

        assertThat(catalog.practice(SLUG).effective()).isEqualTo(proposal.offered());
        assertThat(catalog.practice(SLUG).state()).isEqualTo(CatalogEntryState.FROM_HEPHAESTUS);
        assertThatThrownBy(() -> releases.practiceRelease(SLUG)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void movingACustomizedPracticeDoesNotAcknowledgeAPendingRelease() {
        customizeFromOlderBundle();
        PracticeReleaseProposalDTO pending = releases.practiceRelease(SLUG);
        String destination = otherGroup();

        catalog.placePractice(SLUG, matchCatalog(), destination, 0);

        PracticeReleaseProposalDTO afterMove = releases.practiceRelease(SLUG);
        assertThat(afterMove.base()).isEqualTo(pending.base());
        assertThat(afterMove.offered()).isEqualTo(pending.offered());
        assertThat(afterMove.current().groupSlug()).isEqualTo(destination);
        assertThat(catalog.practice(SLUG).state()).isEqualTo(CatalogEntryState.UPDATE_WAITING);
    }

    @Test
    void movingABundledPracticeFirstRecordsItsAdoptedBase() {
        PracticeDefinition shipped = catalog.practice(SLUG).shipped();
        assertThat(shipped).isNotNull();

        catalog.placePractice(SLUG, matchCatalog(), otherGroup(), 0);

        CuratedPracticeOverride saved = overrides.findBySlug(SLUG).orElseThrow();
        assertThat(saved.getAdoptedBase()).isEqualTo(shipped);
        assertThat(saved.getAcceptedBundledDigest()).isEqualTo(CuratedDefinitionDigest.of(SLUG, shipped));
    }

    private String otherGroup() {
        String current = catalog.practice(SLUG).effective().groupSlug();
        return catalog.catalog().groups().stream()
                .map(CatalogEntry::slug)
                .filter(group -> !group.equals(current))
                .findFirst()
                .orElseThrow();
    }

    private EntityTagPrecondition matchCatalog() {
        return EntityTagPrecondition.parse("\"" + catalog.catalog().etag() + "\"");
    }

    private void customizeFromOlderBundle() {
        PracticeDefinition shipped = catalog.practice(SLUG).effective();
        PracticeDefinition old = withCriteria(shipped, "Earlier criteria");
        CuratedPracticeOverride override = new CuratedPracticeOverride(SLUG, Instant.now());
        override.write(withCriteria(shipped, "Local criteria"), null, Instant.now());
        override.adoptBundledBase(old);
        overrides.save(override);
    }

    private static PracticeDefinition withCriteria(PracticeDefinition source, String criteria) {
        return new PracticeDefinition(
                source.name(),
                source.bindings(),
                criteria,
                source.precomputeScript(),
                source.automatedReviewPolicy(),
                source.whyItMatters(),
                source.whatGoodLooksLike(),
                source.groupSlug(),
                source.deliveryBehavior());
    }

    private static EntityTagPrecondition match(PracticeReleaseProposalDTO proposal) {
        return EntityTagPrecondition.parse("\"" + proposal.etag() + "\"");
    }
}
