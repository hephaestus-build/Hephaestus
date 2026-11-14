package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@WithAdminUser
class PracticeCatalogReadCostIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private PracticeVersionService versions;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private PracticeVersionRepository versionRows;

    @Autowired
    private CatalogOriginPresenter presenter;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private SqlRequestTrace requests;

    @Autowired
    private QueryPlans plans;

    @Autowired
    private WebTestClient http;

    private Workspace workspace;
    private Practice foreign;

    @BeforeEach
    void setUpCatalog() {
        workspace =
                createWorkspace("catalog-cost", "Catalog", "catalog-cost-org", AccountType.ORG, persistUser("admin"));
        create(workspace, 0);
        create(workspace, 1);
        var other = createWorkspace(
                "catalog-other",
                "Other catalog",
                "catalog-other-org",
                AccountType.ORG,
                persistUser("catalog-other-owner"));
        foreign = create(other, 99);
    }

    @Test
    void shouldKeepCatalogListReadsBoundedAsTheListGrows() {
        var small = reads.measure(
                () -> presenter.presentPractices(workspace.getId(), practices.findAllForCatalog(workspace.getId())));
        assertCatalog(small.value(), 2);
        grow();

        var larger = reads.measure(
                () -> presenter.presentPractices(workspace.getId(), practices.findAllForCatalog(workspace.getId())));

        assertCatalog(larger.value(), 8);
        assertThat(larger.value()).containsAll(small.value());
        assertThat(larger.cost().statements())
                .as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    @Test
    void shouldKeepCatalogRequestCostBoundedAsTheListGrows() {
        var small = requests.measure(
                http,
                client -> client.get()
                        .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/practices")
                        .headers(TestAuthUtils.withCurrentUser())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.length()")
                        .isEqualTo(2)
                        .jsonPath("$[0].currentVersion.number")
                        .isEqualTo(2)
                        .jsonPath("$[0].currentVersion.changed[0]")
                        .isEqualTo("CRITERIA")
                        .jsonPath("$[0].currentVersion.createdBy.displayName")
                        .isEqualTo("Catalog creator 0"));
        grow();

        var larger = requests.measure(
                http,
                client -> client.get()
                        .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/practices")
                        .headers(TestAuthUtils.withCurrentUser())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.length()")
                        .isEqualTo(8)
                        .jsonPath("$[7].slug")
                        .isEqualTo("catalog-practice-7")
                        .jsonPath("$[?(@.slug=='" + foreign.getSlug() + "')]")
                        .isEmpty());

        assertThat(larger.statements())
                .as("small=%s, larger=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements());
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    @Test
    void shouldCompareTheVersionSelectedForThePageWhenANewerVersionExists() {
        var selected = reads.measure(() -> practices.findAllForCatalog(workspace.getId()))
                .value();
        var held = selected.getFirst();
        var fresh = practices
                .findByIdAndWorkspaceId(held.getId(), workspace.getId())
                .orElseThrow();
        versions.append(fresh, DefinitionTestFixtures.rename(DefinitionTestFixtures.definition(), "Later title"), null);

        var rows = presenter.presentPractices(workspace.getId(), selected);

        assertThat(rows.getFirst().currentVersion().number()).isEqualTo(2);
        assertThat(rows.getFirst().currentVersion().changed()).containsExactly(PracticeDefinitionField.CRITERIA);
        assertThat(rows.getFirst().currentVersion().reviewChanged()).isTrue();
    }

    @Test
    void shouldKeepAnAbsentCreatorAndFirstVersionWithoutAComparison() {
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "first-without-creator",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                10,
                null));

        var row = presenter.present(workspace.getId(), practice).currentVersion();

        assertThat(row.number()).isEqualTo(1);
        assertThat(row.createdBy()).isNull();
        assertThat(row.changed()).isEmpty();
        assertThat(row.reviewChanged()).isFalse();
    }

    @Test
    void shouldExcludeForeignCurrentVersionsFromTheComparisonRead() {
        var own = practices.findAllForCatalog(workspace.getId()).getFirst().getCurrentVersion();

        long ownId = Objects.requireNonNull(own.getId());
        long foreignId = Objects.requireNonNull(foreign.getCurrentVersion().getId());
        var rows = versionRows.previousDefinitions(workspace.getId(), List.of(ownId, foreignId));

        assertThat(rows)
                .extracting(PracticeVersionRepository.PreviousDefinition::currentVersionId)
                .containsExactly(ownId);
    }

    private void assertCatalog(List<PracticeDTO> rows, int size) {
        assertThat(rows).hasSize(size).extracting(PracticeDTO::slug).doesNotContain(foreign.getSlug());
        for (int index = 0; index < size; index++) {
            var row = rows.get(index);
            assertThat(row.slug()).isEqualTo("catalog-practice-" + index);
            assertThat(row.currentVersion().number()).isEqualTo(2);
            assertThat(row.currentVersion().changed()).containsExactly(PracticeDefinitionField.CRITERIA);
            assertThat(row.currentVersion().reviewChanged()).isTrue();
            assertThat(Objects.requireNonNull(row.currentVersion().createdBy()).displayName())
                    .isEqualTo("Catalog creator " + index);
        }
    }

    private void grow() {
        for (int index = 2; index < 8; index++) create(workspace, index);
    }

    private Practice create(Workspace owner, int index) {
        var creator = persistAccount("Catalog creator " + index);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                owner.getId(),
                "catalog-practice-" + index,
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                index,
                creator.getId()));
        versions.append(
                practice,
                DefinitionTestFixtures.withCriteria(DefinitionTestFixtures.definition(), "Updated criteria " + index),
                creator.getId());
        return practice;
    }
}
