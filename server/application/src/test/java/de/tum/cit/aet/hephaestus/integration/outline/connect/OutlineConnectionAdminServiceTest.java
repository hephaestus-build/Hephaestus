package de.tum.cit.aet.hephaestus.integration.outline.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient.OutlineTokenDescription;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class OutlineConnectionAdminServiceTest extends BaseUnitTest {

    private static final long WS = 7L;
    private static final long CONNECTION_ID = 42L;
    private static final String SERVER_URL = "https://outline.example.test";

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineApiClient apiClient;

    @Mock
    private Connection connection;

    private OutlineConnectionAdminService service() {
        lenient().when(connection.getId()).thenReturn(CONNECTION_ID);
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.OutlineConfig(SERVER_URL, null, null, Set.of()));
        lenient()
                .when(connectionService.findActive(WS, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(connection));
        return new OutlineConnectionAdminService(connectionService, apiClient);
    }

    private void storedToken(String token) {
        when(connectionService.findActiveBearerToken(WS, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(new BearerToken(token, null)));
    }

    @Test
    void tokenStatus_rejectedToken_isNotAccepted_ratherThanAnError() {
        OutlineConnectionAdminService service = service();
        storedToken("ol_dead_key");
        doThrow(new OutlineApiException("Outline /api/auth.info failed (HTTP 401)"))
                .when(apiClient)
                .validateToken(SERVER_URL, "ol_dead_key");

        OutlineTokenStatusDTO status = service.tokenStatus(WS);

        assertThat(status.accepted()).isFalse();
        assertThat(status.name()).isNull();
        assertThat(status.expiresAt()).isNull();
        verify(apiClient, never()).describeToken(any(), any());
    }

    @Test
    void tokenStatus_acceptedAndDescribable_carriesTheKeysMetadata() {
        OutlineConnectionAdminService service = service();
        storedToken("ol_live_key");
        Instant expiresAt = Instant.parse("2026-12-01T10:00:00Z");
        Instant lastActiveAt = Instant.parse("2026-07-13T08:30:00Z");
        when(apiClient.describeToken(SERVER_URL, "ol_live_key"))
                .thenReturn(Optional.of(new OutlineTokenDescription("Hephaestus", "ab12", expiresAt, lastActiveAt)));

        OutlineTokenStatusDTO status = service.tokenStatus(WS);

        assertThat(status.accepted()).isTrue();
        assertThat(status.name()).isEqualTo("Hephaestus");
        assertThat(status.last4()).isEqualTo("ab12");
        assertThat(status.expiresAt()).isEqualTo(expiresAt);
        assertThat(status.lastActiveAt()).isEqualTo(lastActiveAt);
    }

    @Test
    void tokenStatus_acceptedButUndescribable_isAcceptedWithoutMetadata() {
        OutlineConnectionAdminService service = service();
        storedToken("ol_scoped_key");
        when(apiClient.describeToken(SERVER_URL, "ol_scoped_key")).thenReturn(Optional.empty());

        OutlineTokenStatusDTO status = service.tokenStatus(WS);

        assertThat(status.accepted()).isTrue();
        assertThat(status.name()).isNull();
        assertThat(status.last4()).isNull();
        assertThat(status.expiresAt()).isNull();
        assertThat(status.lastActiveAt()).isNull();
    }

    @Test
    void tokenStatus_acceptedButMetadataProbeFails_isAcceptedWithoutMetadata_notAnError() {
        OutlineConnectionAdminService service = service();
        storedToken("ol_live_key");
        when(apiClient.describeToken(SERVER_URL, "ol_live_key"))
                .thenThrow(new OutlineApiException("Outline /api/apiKeys.list failed (HTTP 500)"));

        OutlineTokenStatusDTO status = service.tokenStatus(WS);

        assertThat(status.accepted()).isTrue();
        assertThat(status.name()).isNull();
        assertThat(status.last4()).isNull();
        assertThat(status.expiresAt()).isNull();
        assertThat(status.lastActiveAt()).isNull();
    }

    @Test
    void tokenStatus_withoutAStoredToken_isNotAccepted() {
        OutlineConnectionAdminService service = service();
        when(connectionService.findActiveBearerToken(WS, IntegrationKind.OUTLINE))
                .thenReturn(Optional.empty());

        assertThat(service.tokenStatus(WS).accepted()).isFalse();
        verify(apiClient, never()).validateToken(any(), any());
    }

    @Test
    void tokenStatus_withoutActiveConnection_isNotFound() {
        OutlineConnectionAdminService service = service();
        when(connectionService.findActive(WS, IntegrationKind.OUTLINE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.tokenStatus(WS)).isInstanceOf(EntityNotFoundException.class);
    }
}
