package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeVersionServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeReviewDigest digest;

    @Autowired
    JsonMapper mapper;

    private Practice create(String suffix) {
        var owner = persistUser("version-owner");
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
    void shouldKeepPinnedHistoryAndTheDigestWhenOnlyTheNameChanges() {
        Practice practice = create("name");
        var first = practice.getCurrentVersion();
        var definition = versions.definitionOf(first);
        var second = versions.append(practice, DefinitionTestFixtures.rename(definition, "New display name"), null);
        assertThat(second.getNumber()).isEqualTo(2);
        assertThat(second.getName()).isEqualTo("New display name");
        assertThat(second.getReviewDigest()).isEqualTo(first.getReviewDigest());
        assertThat(versions.definitionOf(first)).isEqualTo(definition);
        assertThat(presenter.viewCurrent(practice).changed()).containsExactly(PracticeDefinitionField.NAME);
        assertThat(presenter.viewCurrent(practice).reviewChanged()).isFalse();
        assertThat(presenter.history(practice))
                .extracting(version -> version.number())
                .containsExactly(2, 1);
    }

    @Test
    void shouldChangeTheDigestWhenCriteriaChange() {
        Practice practice = create("criteria");
        var first = practice.getCurrentVersion();
        var second = versions.append(
                practice, DefinitionTestFixtures.definition("Describe the change", "Different criteria."), null);
        assertThat(second.getReviewDigest()).startsWith("v6:").hasSize(67).isNotEqualTo(first.getReviewDigest());
        assertThat(presenter.viewCurrent(practice).changed()).containsExactly(PracticeDefinitionField.CRITERIA);
        assertThat(presenter.viewCurrent(practice).reviewChanged()).isTrue();
    }

    @Test
    void shouldKeepJudgmentEquivalenceAcrossGuidanceEventAndDeliveryEdits() {
        var practice = create("presentation");
        var original = practice.getCurrentVersion();
        var definition = versions.definitionOf(original);
        var edited = new PracticeDefinition(
                "Updated display name",
                new ReviewOn(List.of(), Map.of()),
                definition.appliesWhen(),
                definition.about(),
                definition.reads(),
                definition.reviewedBy(),
                definition.criteria(),
                definition.script(),
                definition.scan(),
                false,
                new PracticeGuidance("Updated guidance.", null, null, null, null));
        var appended = versions.append(practice, edited, null);
        assertThat(appended.getReviewDigest()).isEqualTo(original.getReviewDigest());
        assertThat(appended.getId()).isNotEqualTo(original.getId());
        assertThat(presenter.viewCurrent(practice).changed())
                .containsExactly(
                        PracticeDefinitionField.NAME,
                        PracticeDefinitionField.REVIEW_ON,
                        PracticeDefinitionField.LINE_NOTES,
                        PracticeDefinitionField.GUIDANCE);
    }

    @Test
    void shouldDistinguishUnknownHistoricalAboutFromExplicitNobodyInTheDatabaseDigest() {
        var historical = (ObjectNode) mapper.valueToTree(DefinitionTestFixtures.definition());
        historical.remove("about");
        var nobody = historical.deepCopy();
        nobody.putNull("about");
        assertThat(digest.of(historical)).isNotEqualTo(digest.of(nobody));
        assertThat(historical.has("about")).isFalse();
    }
}
