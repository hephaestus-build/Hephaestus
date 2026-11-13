package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PracticeServiceUpdateIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeService service;

    @Test
    @WithAdminUser
    void shouldRejectAStaleVersionWithoutAppendingAnotherVersion() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("edit-stale", "Edits", "edit-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "edit-practice",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        String initialId =
                Objects.requireNonNull(practice.getCurrentVersion().getId()).toString();
        var ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(), null);
        assertThatThrownBy(() -> service.updatePractice(
                        ctx,
                        practice.getSlug(),
                        new UpdatePracticeRequestDTO(
                                DefinitionTestFixtures.authoring(DefinitionTestFixtures.rename(
                                        DefinitionTestFixtures.definition(), "New display name")),
                                "9223372036854775807")))
                .isInstanceOf(StalePracticeVersionException.class);
        assertThat(service.getPractice(ctx, practice.getSlug())
                        .getCurrentVersion()
                        .getId())
                .hasToString(initialId);
        assertThat(presenter.history(practice)).hasSize(1);
    }
}
