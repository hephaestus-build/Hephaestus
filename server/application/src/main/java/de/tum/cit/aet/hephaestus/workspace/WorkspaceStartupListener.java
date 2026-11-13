package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.event.WorkspacesInitializedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProvisioningHook;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProvisioning;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Dispatch activation asynchronously so readiness listeners do not wait for provider calls.
 * {@link Order#value()} must let the NATS consumer initialize before {@link WorkspacesInitializedEvent} is published.
 */
@Component
@ConditionalOnBackgroundWork
@ConditionalOnServerRole
@Order(Ordered.LOWEST_PRECEDENCE)
public class WorkspaceStartupListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStartupListener.class);

    private final WorkspaceProvisioning provisioningService;
    private final WorkspaceActivationService workspaceActivationService;
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AsyncTaskExecutor applicationTaskExecutor;
    private final List<WorkspaceProvisioningHook> provisioningHooks;

    public WorkspaceStartupListener(
            WorkspaceProvisioning provisioningService,
            WorkspaceActivationService workspaceActivationService,
            WorkspaceRepository workspaceRepository,
            ApplicationEventPublisher eventPublisher,
            AsyncTaskExecutor applicationTaskExecutor,
            List<WorkspaceProvisioningHook> provisioningHooks) {
        this.provisioningService = provisioningService;
        this.workspaceActivationService = workspaceActivationService;
        this.workspaceRepository = workspaceRepository;
        this.eventPublisher = eventPublisher;
        this.applicationTaskExecutor = applicationTaskExecutor;
        this.provisioningHooks = provisioningHooks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        applicationTaskExecutor.execute(this::provisionAndActivate);
    }

    private void provisionAndActivate() {
        // Phase 1: Provision workspaces (creates/loads workspace entities)
        // Each provider bootstrap is isolated so a failure in one doesn't block others
        log.info("Starting workspace provisioning");
        try {
            provisioningService.bootstrapDefaultPatWorkspace();
        } catch (Exception e) {
            log.error("GitHub PAT workspace bootstrap failed, continuing with other providers", e);
        }
        try {
            provisioningService.bootstrapDefaultGitLabPatWorkspace();
        } catch (Exception e) {
            log.error("GitLab PAT workspace bootstrap failed, continuing with other providers", e);
        }
        for (WorkspaceProvisioningHook hook : provisioningHooks) {
            try {
                hook.reconcile();
            } catch (Exception e) {
                log.error("Provisioning hook failed: kind={}", hook.kind(), e);
            }
        }

        // Phase 2: Signal that workspaces are initialized
        // Installation consumer can now start - it only needs workspaces to exist
        int workspaceCount = (int) workspaceRepository.count();
        log.info("Workspaces initialized: count={}", workspaceCount);
        eventPublisher.publishEvent(new WorkspacesInitializedEvent(workspaceCount));

        // Phase 3: Activate workspaces in parallel (repository reconciliation and full sync can
        // take a while)
        workspaceActivationService.activateAllWorkspaces();
    }
}
