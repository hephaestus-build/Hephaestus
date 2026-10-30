package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeContractUpgradeIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeVersionRepository history;

    @Autowired
    PracticeRepository practices;

    @Autowired
    JsonMapper mapper;

    @Test
    void shouldAppendTheCurrentSourceContractAndPreserveRecordedHistoricalInputs() {
        var owner = persistUser("version-owner");
        var workspace = createWorkspace("version-contract", "Versions", "version-org", AccountType.ORG, owner);
        var authored = DefinitionTestFixtures.definition();
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace, "owned-contract", new ItemKind("scm.pull_request"), authored, null, 0, null));
        var recorded = (ObjectNode) mapper.valueToTree(authored);
        ((ObjectNode) recorded.path("reads")).put("contract", "1.3.0");
        var legacy = history.saveAndFlush(new PracticeVersion(practice, 2, recorded, null));
        var previousBytes = legacy.getDefinition();
        var previousId = Objects.requireNonNull(legacy.getId());
        assertThatThrownBy(() -> versions.append(practice, versions.definitionOf(legacy), null))
                .isInstanceOf(IllegalArgumentException.class);
        var appended = versions.append(practice, authored, null);
        var retained = history.findById(previousId).orElseThrow();
        assertThat(retained.getDefinition()).isEqualTo(previousBytes);
        assertThat(versions.definitionOf(retained).reads().contract()).isEqualTo("1.3.0");
        assertThat(appended.getNumber()).isEqualTo(3);
        assertThat(versions.definitionOf(appended).reads().contract()).isEqualTo("3.0.0");
        assertThat(versions.definitionOf(appended).criteria())
                .isEqualTo(versions.definitionOf(retained).criteria());
        assertThat(versions.definitionOf(appended).guidance())
                .isEqualTo(versions.definitionOf(retained).guidance());
        assertThat(practices
                        .findByWorkspaceIdAndSlug(workspace.getId(), practice.getSlug())
                        .orElseThrow()
                        .getCurrentVersion()
                        .getId())
                .isEqualTo(appended.getId());
        assertThat(presenter.history(practice))
                .extracting(version -> version.number())
                .containsExactly(3, 2, 1);
    }
}
