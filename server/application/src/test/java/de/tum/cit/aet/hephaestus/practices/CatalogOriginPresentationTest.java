package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogChangeKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogEntryState;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogOffer;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.catalog.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogOriginPresentationTest extends BaseUnitTest {
    @Test
    void shouldLeaveReviewDigestAbsentForAGroupWithoutAJudgmentDefinition() {
        var definition = new GroupDefinition("Review", "Review practices", null, null);
        var offer = new CatalogOffer<>(
                "review",
                definition,
                definition,
                definition,
                0,
                false,
                CatalogEntryState.FROM_HEPHAESTUS,
                CatalogChangeKind.NONE,
                "group-etag",
                true);
        var catalog = mock(CatalogService.class);
        when(catalog.effectiveCatalog()).thenReturn(new EffectiveCatalog(List.of(offer), List.of(), "catalog-etag"));
        var group = new PracticeGroup();
        group.setId(42L);
        group.setSlug("review");
        group.setName(definition.name());
        group.setDescription(definition.description());
        group.setCatalogSlug("review");
        group.setCatalogBase(definition);
        group.setCreatedAt(Instant.EPOCH);

        var dto = new CatalogOriginPresenter(
                        catalog,
                        mock(PracticeReviewDigest.class),
                        mock(PracticeVersionService.class),
                        mock(WorkspaceReviewDefaultsProvider.class),
                        mock(PracticeVersionRepository.class),
                        mock(AccountReferenceService.class))
                .present(10L, group);

        assertThat(dto.catalogOrigin().state()).isEqualTo(CatalogEntryState.FROM_HEPHAESTUS);
        assertThat(dto.catalogOrigin().catalogSlug()).isEqualTo("review");
        assertThat(dto.catalogOrigin().offeredDigest()).isNull();
    }

    @Test
    void shouldPresentAnUnlinkedPracticeAsLocalWithInstanceInheritance() {
        var versions = mock(PracticeVersionService.class);
        var defaults = mock(WorkspaceReviewDefaultsProvider.class);
        var version = TestEntities.practiceVersion();
        var practice = new Practice();
        practice.setId(9007199254740993L);
        practice.setSlug("local");
        practice.setKind(new ItemKind("scm.pull_request"));
        practice.setCurrentVersion(version);
        practice.setCreatedAt(Instant.EPOCH);
        var current = new PracticeVersionDTO(
                "12",
                1,
                "Local practice",
                DefinitionTestFixtures.definition(),
                "v6:" + "a".repeat(64),
                Instant.EPOCH,
                null,
                List.of(),
                false);
        when(versions.view(version, null, null)).thenReturn(current);
        var dto = new CatalogOriginPresenter(
                        mock(CatalogService.class),
                        mock(PracticeReviewDigest.class),
                        versions,
                        defaults,
                        mock(PracticeVersionRepository.class),
                        mock(AccountReferenceService.class))
                .present(10L, practice);
        assertThat(dto.id()).isEqualTo("9007199254740993");
        assertThat(dto.currentVersion()).isEqualTo(current);
        assertThat(dto.catalogOrigin().state()).isEqualTo(CatalogEntryState.YOURS);
        assertThat(dto.feedbackMode().effective()).isEqualTo(FeedbackMode.APPROVE_FIRST);
        assertThat(dto.feedbackMode().source()).isEqualTo(FeedbackModeSource.INSTANCE);
    }
}
