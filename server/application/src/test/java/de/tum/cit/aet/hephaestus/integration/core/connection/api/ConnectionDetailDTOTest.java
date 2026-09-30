package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@link ConnectionDetailDTO} javadoc promises the payload "NEVER carries credentials". That
 * held vacuously until {@code OutlineConfig.webhookSecret} — the AES-GCM ciphertext of the live HMAC
 * signing secret — joined the sealed config, at which point {@code GET /connections/{id}} started
 * handing it to every workspace admin's browser. These tests pin the redaction and the invariant
 * that a future secret-shaped config component cannot be added without listing it.
 */
@Tag("unit")
class ConnectionDetailDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void outlineConfig_dropsTheWebhookSecretButKeepsTheRestOfTheConfig() {
        Connection connection = outlineConnection(new ConnectionConfig.OutlineConfig(
                "https://o.test", "sub-1", "ENC:v2:super-secret", Set.of("documents")));

        ConnectionDetailDTO dto = ConnectionDetailDTO.from(connection, manifests(), mapper);

        assertThat(dto.config()).doesNotContainKey("webhookSecret");
        assertThat(dto.config()).containsEntry("serverUrl", "https://o.test");
        assertThat(dto.config()).containsEntry("webhookSubscriptionId", "sub-1");
        assertThat(dto.config()).containsEntry("type", "OUTLINE");
    }

    @Test
    void theSerializedResponseBodyContainsNoTraceOfTheSecret() {
        Connection connection = outlineConnection(
                new ConnectionConfig.OutlineConfig("https://o.test", "sub-1", "ENC:v2:super-secret", Set.of()));

        String json = mapper.writeValueAsString(ConnectionDetailDTO.from(connection, manifests(), mapper));

        assertThat(json).doesNotContain("webhookSecret").doesNotContain("ENC:v2:super-secret");
        assertThat(json).contains("sub-1");
    }

    @Test
    void aConfigWithoutSecretsIsPassedThroughUnchanged() {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of("issues")));
        stampPersistenceFields(connection);

        ConnectionDetailDTO dto = ConnectionDetailDTO.from(connection, manifests(), mapper);

        assertThat(dto.config()).containsEntry("orgLogin", "acme").containsKey("installationId");
        assertThat(dto.config()).containsKey("serverUrl"); // null-valued keys survive redaction
    }

    @Test
    void shouldExposeGitlabExpiryOnlyThroughTypedSafeFields() {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        LocalDate expiry = LocalDate.of(2026, 12, 5);
        Instant checked = Instant.parse("2026-09-30T03:00:00Z");
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "group",
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        new ConnectionConfig.GitLabTokenMetadata(expiry, checked)));
        stampPersistenceFields(connection);
        ConnectionDetailDTO dto = ConnectionDetailDTO.from(connection, manifests(), mapper);
        assertThat(dto.tokenExpiresAt()).isEqualTo(expiry);
        assertThat(dto.tokenExpiryCheckedAt()).isEqualTo(checked);
        assertThat(dto.config()).doesNotContainKey("tokenMetadata");
        assertThat(mapper.writeValueAsString(dto)).contains("2026-12-05").doesNotContain("tokenMetadata");
    }

    /**
     * Guard rail, not a behaviour test: any {@link ConnectionConfig} record component whose name
     * smells like a credential must be in {@link ConnectionDetailDTO#SENSITIVE_CONFIG_KEYS}. Without
     * this, the next integration that stashes a token in its config silently re-opens the leak.
     */
    @Test
    void everySecretShapedConfigComponentIsRegisteredAsSensitive() {
        List<String> unregistered = new ArrayList<>();
        for (Class<?> variant : ConnectionConfig.class.getPermittedSubclasses()) {
            RecordComponent[] components = variant.getRecordComponents();
            if (components == null) {
                continue;
            }
            for (RecordComponent component : components) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                boolean secretShaped = name.contains("secret")
                        || name.contains("token")
                        || name.contains("password")
                        || name.contains("credential")
                        || name.contains("privatekey");
                if (secretShaped && !ConnectionDetailDTO.SENSITIVE_CONFIG_KEYS.contains(name)) {
                    unregistered.add(variant.getSimpleName() + "." + component.getName());
                }
            }
        }
        assertThat(unregistered)
                .as(
                        "add these to ConnectionDetailDTO.SENSITIVE_CONFIG_KEYS (lowercased) — they would be served by GET /connections/{id}")
                .isEmpty();
    }

    private Connection outlineConnection(ConnectionConfig config) {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        Connection connection = new Connection(workspace, IntegrationKind.OUTLINE, "https://o.test", config);
        connection.setState(IntegrationState.ACTIVE);
        stampPersistenceFields(connection);
        return connection;
    }

    private IntegrationManifestRegistry manifests() {
        IntegrationManifestRegistry registry = mock(IntegrationManifestRegistry.class);
        when(registry.capabilitiesFor(org.mockito.ArgumentMatchers.any())).thenReturn(Set.of());
        return registry;
    }

    /** id/createdAt/updatedAt normally come from JPA; the DTO would NPE on null Instants. */
    private static void stampPersistenceFields(Connection c) {
        try {
            Field id = Connection.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(c, 1L);
            Field createdAt = Connection.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(c, Instant.parse("2026-01-01T00:00:00Z"));
            Field updatedAt = Connection.class.getDeclaredField("updatedAt");
            updatedAt.setAccessible(true);
            updatedAt.set(c, Instant.parse("2026-01-02T00:00:00Z"));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
