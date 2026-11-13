package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeGroupServiceTest extends BaseUnitTest {
    @Test
    void shouldClearOptionalGroupFieldsWhenTheReplacementContainsNull() {
        var repository = mock(PracticeGroupRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setId(10L);
        var group = new PracticeGroup();
        group.setId(20L);
        group.setName("Old name");
        group.setDescription("Old description");
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(repository.findByWorkspaceIdAndSlug(10L, "reviews")).thenReturn(Optional.of(group));
        var service = new PracticeGroupService(
                repository,
                mock(PracticeRepository.class),
                WorkspacePortFixtures.queries(workspaces),
                mock(ConfigAuditPort.class),
                JsonMapper.builder().build());
        var saved = service.updateGroup(
                new WorkspaceContext(10L, "workspace", "Workspace", null, null, Set.of()),
                "reviews",
                new GroupAttributes("New name", null, null, null, null),
                false);
        assertThat(saved.getName()).isEqualTo("New name");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.isShownToDevelopers()).isFalse();
    }

    @Test
    void shouldRestoreCatalogBindingWithoutChangingTheVersionOrFeedbackMode() {
        var repository = mock(PracticeGroupRepository.class);
        var practices = mock(PracticeRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setId(10L);
        var destination = new PracticeGroup();
        destination.setId(20L);
        var original = new PracticeGroup();
        original.setId(30L);
        var practice = new Practice();
        practice.setId(40L);
        practice.setWorkspace(workspace);
        practice.setKind(new ItemKind("scm.pull_request"));
        practice.setGroup(original);
        practice.setPosition(9);
        practice.setFeedbackMode(FeedbackMode.OFF);
        var version = new PracticeVersion(
                practice, 1, JsonMapper.builder().build().valueToTree(DefinitionTestFixtures.definition()), null);
        practice.setCurrentVersion(version);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(repository.findByWorkspaceIdAndSlug(10L, "destination")).thenReturn(Optional.of(destination));
        when(practices.findMaxPosition(10L, 20L)).thenReturn(3);
        var service = new PracticeGroupService(
                repository,
                practices,
                WorkspacePortFixtures.queries(workspaces),
                mock(ConfigAuditPort.class),
                JsonMapper.builder().build());
        service.restoreCatalogBinding(
                new WorkspaceContext(10L, "workspace", "Workspace", null, null, Set.of()), practice, "destination");
        assertThat(practice.getGroup()).isSameAs(destination);
        assertThat(practice.getPosition()).isEqualTo(4);
        assertThat(practice.getCurrentVersion()).isSameAs(version);
        assertThat(practice.getFeedbackMode()).isEqualTo(FeedbackMode.OFF);
    }
}
