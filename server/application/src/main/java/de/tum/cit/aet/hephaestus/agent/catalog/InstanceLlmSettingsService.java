package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmSettingsAudit;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads on every runtime role, because the workspace BYO gate consumes it. {@link LlmSettingsAudit} is
 * therefore taken as an {@link ObjectProvider} — a hard dependency on its server-role-only
 * implementation would fail the context on worker and webhook.
 */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance LLM settings singleton is global (app_admin-owned), not tenant-scoped")
public class InstanceLlmSettingsService {

    static final short SINGLETON_ID = 1;

    private final InstanceLlmSettingsRepository settingsRepository;
    private final ObjectProvider<LlmSettingsAudit> llmSettingsAuditProvider;

    @Transactional(readOnly = true)
    public InstanceLlmSettings get() {
        return settingsRepository.findById(SINGLETON_ID).orElseGet(InstanceLlmSettingsService::defaults);
    }

    @Transactional
    public InstanceLlmSettings update(UpdateInstanceLlmSettingsRequestDTO request) {
        InstanceLlmSettings settings = settingsRepository
                .findByIdForUpdate(SINGLETON_ID)
                .orElseGet(() -> {
                    InstanceLlmSettings created = defaults();
                    created.setId(SINGLETON_ID);
                    return created;
                });

        if (request.allowedEgressHosts() != null) {
            String hosts = request.allowedEgressHosts().isBlank()
                    ? null
                    : request.allowedEgressHosts().trim();
            settings.setAllowedEgressHosts(hosts);
        }
        if (request.allowWorkspaceConnections() != null) {
            settings.setAllowWorkspaceConnections(request.allowWorkspaceConnections());
        }

        settings.setUpdatedAt(Instant.now());
        settings.setUpdatedByAccountId(SecurityUtils.getCurrentAccountId().orElse(null));
        InstanceLlmSettings saved = settingsRepository.save(settings);

        LlmSettingsAudit llmSettingsAudit = llmSettingsAuditProvider.getIfAvailable();
        if (llmSettingsAudit != null) {
            llmSettingsAudit.settingsChanged(saved.isAllowWorkspaceConnections());
        }
        return saved;
    }

    private static InstanceLlmSettings defaults() {
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setId(SINGLETON_ID);
        settings.setAllowWorkspaceConnections(true);
        return settings;
    }
}
