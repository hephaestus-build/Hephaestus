package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingPrivacyJournal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** A workspace purge records its privacy fact once: a repeated purge of a purged workspace erases nothing. */
class WorkspaceLifecycleServiceTest extends BaseUnitTest {

    @Test
    void shouldRecordTheWorkspacePurgeOnceWhenThePurgeIsRepeated() {
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setId(7L);
        workspace.setWorkspaceSlug("purged-workspace");
        when(workspaces.findByWorkspaceSlugForUpdate("purged-workspace")).thenReturn(Optional.of(workspace));
        when(workspaces.save(workspace)).thenReturn(workspace);
        var journal = new RecordingPrivacyJournal();
        var service = new WorkspaceLifecycleService(
                workspaces,
                mock(MembershipRepository.class),
                mock(WorkspaceSlugHistoryRepository.class),
                mock(WorkspaceSlugReservationRepository.class),
                mock(WorkspaceNamespaceReservation.class),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                mock(PlatformTransactionManager.class),
                mock(ConfigAuditPort.class),
                journal,
                mock(RowSweepAdmission.class));

        service.purgeWorkspace("purged-workspace");
        service.purgeWorkspace("purged-workspace");

        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
        assertThat(journal.facts())
                .containsExactly(new RecordingPrivacyJournal.Fact(
                        PrivacyJournalKind.WORKSPACE_PURGED, Map.of("workspaceId", 7L)));
    }
}
