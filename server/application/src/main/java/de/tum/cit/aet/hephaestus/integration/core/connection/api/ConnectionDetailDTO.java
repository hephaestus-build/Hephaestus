package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationFamily;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Detailed view of a single Connection — extends {@link ConnectionSummaryDTO} with the
 * sealed config serialized to a free-form JSON object ({@code Map<String, Object>} →
 * {@code type: object, additionalProperties: true} in the spec, so it round-trips through
 * client codegen). NEVER carries credentials: the encrypted credential blob stays inside the
 * entity, and every secret-bearing key of the config itself is stripped by
 * {@code redactSensitive} before serialization.
 *
 * <p><b>Why redact here and not with {@code @JsonIgnore} on the record component?</b> The very
 * same {@code ObjectMapper} bean serializes {@code ConnectionConfig} into the JSONB
 * {@code connection.config} column (Hibernate's {@code json_format_mapper} is wired to it in
 * {@code HibernateJacksonFormatMapperConfig}). Annotating the component would therefore drop the
 * secret on write and destroy the stored value — the API-boundary filter below is the only place
 * the two concerns can be separated.
 *
 * <p>Mirroring the summary fields (rather than embedding the summary record) keeps
 * the JSON shape flat — the API consumer sees one record, not a nested {@code summary}
 * object.
 */
public record ConnectionDetailDTO(
        Long id,
        IntegrationKind kind,
        IntegrationFamily family,
        IntegrationState state,
        @Nullable String instanceKey,
        @Nullable String displayName,
        @Nullable String stateReason,
        Instant createdAt,
        Instant updatedAt,
        Set<Capability> capabilities,
        @Nullable Map<String, Object> config,
        @Nullable Instant credentialsUnreadableSince,
        @Nullable LocalDate tokenExpiresAt,
        @Nullable Instant tokenExpiryCheckedAt,
        @Nullable Problem attentionProblem) {
    /**
     * Private config keys which must never cross the free-form API boundary — matched
     * case-insensitively against the serialized config map.
     *
     * <p>GitLab token metadata is also omitted here: its safe dates have typed DTO fields, not a
     * second free-form wire representation. It contains no token value.
     *
     * <p>Currently {@code webhookSecret} on {@code OutlineConfig}: the AES-GCM ciphertext of the
     * live HMAC signing secret of the workspace's Outline change-notification subscription. Leaking
     * it to the browser (and to every logging proxy in between) hands an attacker the material to
     * forge signed deliveries.
     *
     * <p>{@code ConnectionDetailDTOTest#everySecretShapedConfigComponentIsRegisteredAsSensitive}
     * reflects over every {@code ConnectionConfig} subtype and fails if a
     * new secret-shaped component is added without being listed here.
     */
    public static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of("webhooksecret", "tokenmetadata");

    @SuppressWarnings("unchecked")
    public static ConnectionDetailDTO from(Connection c, IntegrationManifestRegistry manifests, ObjectMapper mapper) {
        Map<String, Object> configMap =
                c.getConfig() == null ? null : redactSensitive(mapper.convertValue(c.getConfig(), Map.class));
        var tokenMetadata =
                c.getConfig() instanceof ConnectionConfig.GitLabConfig gitlab ? gitlab.tokenMetadata() : null;
        return new ConnectionDetailDTO(
                c.getId(),
                c.getKind(),
                c.getKind().family(),
                c.getState(),
                c.getInstanceKey(),
                c.getDisplayName(),
                c.getStateReason(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                manifests.capabilitiesFor(c.getKind()),
                configMap,
                c.credentialsUnreadableSince(),
                tokenMetadata == null ? null : tokenMetadata.expiresAt(),
                tokenMetadata == null ? null : tokenMetadata.checkedAt(),
                c.getAttentionProblem());
    }

    /**
     * Drops every {@code SENSITIVE_CONFIG_KEYS} entry. The key is removed outright rather than
     * masked with a placeholder: a {@code "***"} value would round-trip back through an admin
     * "edit config" client as a literal secret. Keys are compared lowercased with {@link
     * Locale#ROOT} (the {@code LocaleSafetyArchTest} contract).
     */
    static Map<String, Object> redactSensitive(Map<String, Object> raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key != null && SENSITIVE_CONFIG_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // LinkedHashMap (not Map.copyOf): config components are @Nullable and Jackson emits
            // them as JSON nulls, which Map.copyOf rejects.
            safe.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(safe);
    }
}
