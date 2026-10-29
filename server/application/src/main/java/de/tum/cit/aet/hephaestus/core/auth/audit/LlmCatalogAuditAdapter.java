package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.auth.spi.LlmConnectionAudit;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmModelAudit;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmSettingsAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * In-{@code core.auth} implementation of {@link LlmConnectionAudit}, {@link LlmModelAudit} and
 * {@link LlmSettingsAudit}, so {@link AuthEventLogger} and the {@code LLM_*} event types stay
 * encapsulated here and {@code agent.catalog} consumes only the ports. They stay split by consumer to
 * keep each interface under the SPI method-count ceiling.
 */
@ConditionalOnServerRole
@Component
public class LlmCatalogAuditAdapter implements LlmConnectionAudit, LlmModelAudit, LlmSettingsAudit {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthEventLogger authEventLogger;

    public LlmCatalogAuditAdapter(AuthEventLogger authEventLogger) {
        this.authEventLogger = authEventLogger;
    }

    @Override
    public void connectionCreated(Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_CONNECTION_CREATED, connectionDetails(connectionId, slug));
    }

    @Override
    public void connectionUpdated(Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_CONNECTION_UPDATED, connectionDetails(connectionId, slug));
    }

    @Override
    public void connectionDeleted(Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_CONNECTION_DELETED, connectionDetails(connectionId, slug));
    }

    @Override
    public void modelCreated(Long modelId, Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_MODEL_CREATED, modelDetails(modelId, connectionId, slug));
    }

    @Override
    public void modelUpdated(Long modelId, Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_MODEL_UPDATED, modelDetails(modelId, connectionId, slug));
    }

    @Override
    public void modelDeleted(Long modelId, Long connectionId, String slug) {
        record(AuthEvent.EventType.LLM_MODEL_DELETED, modelDetails(modelId, connectionId, slug));
    }

    @Override
    public void modelPriceChanged(Long modelId, String pricingMode) {
        record(AuthEvent.EventType.LLM_MODEL_PRICE_CHANGED, details("modelId", modelId, "pricingMode", pricingMode));
    }

    @Override
    public void modelSharingChanged(Long modelId, String visibility, int workspaceCount) {
        record(
                AuthEvent.EventType.LLM_MODEL_SHARING_CHANGED,
                details("modelId", modelId, "visibility", visibility, "workspaceCount", workspaceCount));
    }

    @Override
    public void settingsChanged(boolean allowWorkspaceConnections) {
        record(
                AuthEvent.EventType.LLM_SETTINGS_CHANGED,
                details("allowWorkspaceConnections", allowWorkspaceConnections));
    }

    @Override
    public void callPoolChanged(Long poolId, String slug, String change) {
        record(AuthEvent.EventType.LLM_SETTINGS_CHANGED, details("callPoolId", poolId, "slug", slug, "change", change));
    }

    private void record(AuthEvent.EventType type, Map<String, Object> details) {
        authEventLogger
                .event(type, AuthEvent.Result.SUCCESS)
                .actingAccount(SecurityUtils.getCurrentAccountId().orElse(null))
                .details(JSON.writeValueAsString(details))
                .record();
    }

    private static Map<String, Object> connectionDetails(Long connectionId, String slug) {
        return details("connectionId", connectionId, "slug", slug);
    }

    private static Map<String, Object> modelDetails(Long modelId, Long connectionId, String slug) {
        return details("modelId", modelId, "connectionId", connectionId, "slug", slug);
    }

    /** Builds the {@code details} object from alternating key/value pairs, preserving declaration order. */
    private static Map<String, Object> details(Object... keyValuePairs) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            details.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return details;
    }
}
