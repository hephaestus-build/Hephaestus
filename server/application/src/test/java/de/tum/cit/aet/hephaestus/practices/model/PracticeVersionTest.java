package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeVersionTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldKeepTheRecordedDefinitionWhenItsInputAndReadProjectionAreEdited() {
        var practice = practice();
        var original = DefinitionTestFixtures.definition();
        var authored = (ObjectNode) mapper.valueToTree(original);
        var version = TestEntities.version(practice, 3, authored, 91L);
        authored.put("criteria", "Later authored criteria");
        var projection = (ObjectNode) version.getDefinition();
        projection.put("criteria", "Edited read projection");
        assertThat(version.getDefinition()).isEqualTo(mapper.valueToTree(original));
        assertThat(version.getNumber()).isEqualTo(3);
        assertThat(version.getWorkspaceId()).isEqualTo(41L);
        assertThat(version.getCreatedByAccountId()).isEqualTo(91L);
    }

    @Test
    void shouldPreserveMissingHistoricalFieldsWithoutBorrowingTheCurrentDefinition() {
        var practice = practice();
        var historical = mapper.createObjectNode().put("criteria", "Recorded historical criteria");
        var version = TestEntities.version(practice, 1, historical, null);
        practice.setCurrentVersion(
                TestEntities.version(practice, 2, mapper.valueToTree(DefinitionTestFixtures.definition()), null));
        assertThat(version.getDefinition()).isEqualTo(historical);
        assertThat(version.getDefinition().has("about")).isFalse();
        assertThat(version.getDefinition().has("reads")).isFalse();
        assertThat(version.getCreatedByAccountId()).isNull();
    }

    @Test
    void shouldRejectAnInvalidVersionNumberOrAnInvalidRecordedDocument() {
        var practice = practice();
        var definition = mapper.valueToTree(DefinitionTestFixtures.definition());
        assertThatThrownBy(() -> TestEntities.version(practice, 0, definition, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestEntities.version(practice, 1, mapper.createArrayNode(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Practice practice() {
        var workspace = new Workspace();
        workspace.setId(41L);
        var practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setKind(new ItemKind("scm.pull_request"));
        practice.setSlug("clear-feedback");
        return practice;
    }
}
