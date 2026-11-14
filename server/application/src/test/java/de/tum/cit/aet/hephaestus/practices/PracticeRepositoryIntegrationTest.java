package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PracticeRepositoryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeRepository practices;

    @Autowired
    PracticeVersionRepository versionRows;

    private Practice create(String suffix) {
        var owner = persistUser("version-owner-" + suffix);
        var workspace = createWorkspace("version-" + suffix, "Versions", "version-org", AccountType.ORG, owner);
        return versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "owned-" + suffix,
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
    }

    @Test
    void shouldReadOnlyTheOwnedVersionAtTheRequestedTime() {
        var own = create("as-of-own");
        var foreign = create("as-of-foreign");
        var first = own.getCurrentVersion();
        var latest = versions.append(
                own,
                DefinitionTestFixtures.withCriteria(DefinitionTestFixtures.definition(), "Updated criteria."),
                null);
        long workspaceId = own.getWorkspace().getId();
        var practiceIds = List.of(Objects.requireNonNull(own.getId()), Objects.requireNonNull(foreign.getId()));
        long firstVersionId = Objects.requireNonNull(first.getId());
        long latestVersionId = Objects.requireNonNull(latest.getId());
        long foreignVersionId =
                Objects.requireNonNull(foreign.getCurrentVersion().getId());
        var versionIds = List.of(firstVersionId, latestVersionId, foreignVersionId);

        assertThat(versionRows.findByWorkspaceIdAndIdIn(workspaceId, versionIds))
                .extracting(PracticeVersion::getId)
                .containsExactlyInAnyOrder(firstVersionId, latestVersionId);
        assertThat(versionRows.findAt(workspaceId, practiceIds, first.getCreatedAt()))
                .extracting(PracticeVersion::getId)
                .containsExactly(firstVersionId);
        assertThat(versionRows.findAt(workspaceId, practiceIds, latest.getCreatedAt()))
                .extracting(PracticeVersion::getId)
                .containsExactly(latestVersionId);
        assertThat(versionRows.findAt(
                        workspaceId, practiceIds, first.getCreatedAt().minusMillis(1)))
                .isEmpty();
    }

    @Test
    void shouldKeepAnOffPracticeInTheTenantKindQuery() {
        Practice practice = create("query");
        practice.setFeedbackMode(FeedbackMode.OFF);
        practices.saveAndFlush(practice);
        assertThat(practices.findByWorkspaceIdAndKind(practice.getWorkspace().getId(), practice.getKind()))
                .extracting(Practice::getId)
                .contains(practice.getId());
        assertThat(practices.findByWorkspaceIdAndKind(practice.getWorkspace().getId() + 1, practice.getKind()))
                .isEmpty();
    }

    @Test
    void shouldScopePracticeAndVersionLookupsToTheOwningWorkspace() {
        var own = create("lookup-own");
        var foreign = create("lookup-foreign");
        var workspaceId = own.getWorkspace().getId();
        var ownId = Objects.requireNonNull(own.getId());
        var foreignId = Objects.requireNonNull(foreign.getId());
        var versionId = Objects.requireNonNull(own.getCurrentVersion().getId());
        assertThat(practices.findByIdAndWorkspaceId(ownId, workspaceId)).isPresent();
        assertThat(practices.findByIdAndWorkspaceId(foreignId, workspaceId)).isEmpty();
        assertThat(practices.findByWorkspaceIdAndIdIn(workspaceId, List.of(ownId, foreignId)))
                .extracting(Practice::getId)
                .containsExactly(ownId);
        assertThat(versionRows.findByIdAndWorkspaceId(versionId, workspaceId)).isPresent();
        assertThat(versionRows.findByIdAndWorkspaceId(
                        versionId, foreign.getWorkspace().getId()))
                .isEmpty();
        assertThat(versionRows.findByWorkspaceIdAndPracticeIdOrderByNumberAsc(workspaceId, ownId))
                .extracting(version -> version.getId())
                .containsExactly(versionId);
        assertThat(versionRows.findByWorkspaceIdAndPracticeIdOrderByNumberAsc(workspaceId, foreignId))
                .isEmpty();
    }
}
