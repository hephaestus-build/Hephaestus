package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Once a server is ready, it reads the token limits of each enabled model that has none from its provider ({@link
 * LlmModelLimitDiscovery}): a model that an admin added before Hephaestus read limits from providers runs on after the
 * upgrade, without anyone testing its connection. A model that is still unbounded stays visible in AI providers and in
 * {@link LlmModelLimitsHealthIndicator}.
 */
@Component
@ConditionalOnBackgroundWork
@ConditionalOnServerRole
@WorkspaceAgnostic("Startup limit discovery covers every workspace's own models, each probed in its own workspace")
public class LlmModelLimitStartup {

    private static final Logger log = LoggerFactory.getLogger(LlmModelLimitStartup.class);

    private final LlmModelRepository modelRepository;
    private final WorkspaceLlmModelRepository workspaceModelRepository;
    private final LlmModelLimitDiscovery discovery;
    private final AsyncTaskExecutor applicationTaskExecutor;

    LlmModelLimitStartup(
            LlmModelRepository modelRepository,
            WorkspaceLlmModelRepository workspaceModelRepository,
            LlmModelLimitDiscovery discovery,
            AsyncTaskExecutor applicationTaskExecutor) {
        this.modelRepository = modelRepository;
        this.workspaceModelRepository = workspaceModelRepository;
        this.discovery = discovery;
        this.applicationTaskExecutor = applicationTaskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        applicationTaskExecutor.execute(this::discoverUnboundedModels);
    }

    void discoverUnboundedModels() {
        Set<Long> connections = new LinkedHashSet<>();
        for (LlmModel model : modelRepository.findAllWithConnection())
            if (model.isEnabled()
                    && model.getConnection().isEnabled()
                    && !model.limits().bounded())
                connections.add(model.getConnection().getId());
        for (Long connectionId : connections) {
            try {
                discovery.probeInstanceConnection(connectionId);
            } catch (RuntimeException e) {
                log.warn("Startup limit discovery failed: scope=INSTANCE, connectionId={}", connectionId, e);
            }
        }
        record WorkspaceConnection(long workspaceId, long connectionId) {}
        Set<WorkspaceConnection> workspaceConnections = new LinkedHashSet<>();
        for (WorkspaceLlmModel model : workspaceModelRepository.findAllEnabledWithEnabledConnection())
            if (!model.limits().bounded())
                workspaceConnections.add(new WorkspaceConnection(
                        model.getWorkspace().getId(), model.getConnection().getId()));
        for (WorkspaceConnection target : workspaceConnections) {
            try {
                discovery.probeWorkspaceConnection(target.workspaceId(), target.connectionId());
            } catch (RuntimeException e) {
                log.warn(
                        "Startup limit discovery failed: scope=WORKSPACE, workspaceId={}, connectionId={}",
                        target.workspaceId(),
                        target.connectionId(),
                        e);
            }
        }
    }
}
