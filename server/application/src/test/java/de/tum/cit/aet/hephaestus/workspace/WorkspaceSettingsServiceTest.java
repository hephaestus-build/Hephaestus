package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.connection.BearerTokenReplacement;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class WorkspaceSettingsServiceTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** Re-entering a token is the way out for one the server cannot read, so it must not read it. */
    @Test
    void shouldRotateATokenWithoutReadingTheOldOne() {
        WorkspaceSettingsService service = new WorkspaceSettingsService(
                workspaceRepository,
                configAudit,
                connectionService,
                eventPublisher,
                Clock.fixed(Instant.parse("2026-09-05T08:00:00Z"), ZoneOffset.UTC));
        Workspace workspace = TestEntities.workspace(7L);
        Connection connection = new Connection(
                workspace, IntegrationKind.GITHUB, "acme", new ConnectionConfig.GitHubPatConfig("x", "x", Set.of()));
        connection.setCredentials(new BearerToken("ghp-old", null), new CredentialBundleConverter(KEY, false));
        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(workspace));
        when(connectionService.findActiveProviderKind(7L)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.rotateBearerToken(eq(7L), eq(IntegrationKind.GITHUB), any(BearerToken.class)))
                .thenReturn(Optional.of(new BearerTokenReplacement(connection, true)));

        service.updateToken(7L, "ghp-new");

        verify(connectionService).rotateBearerToken(eq(7L), eq(IntegrationKind.GITHUB), any(BearerToken.class));
        // The audit says what the write reported, not what an earlier look at the row suggested.
        ArgumentCaptor<ConfigAuditEntry> audited = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(audited.capture());
        assertThat(audited.getValue().before())
                .isEqualTo(new WorkspaceAuditSnapshots.TokenSnapshot(true, "GITHUB", null));
        verify(connectionService, never()).findActiveBearerToken(any(Long.class), any());
    }
}
