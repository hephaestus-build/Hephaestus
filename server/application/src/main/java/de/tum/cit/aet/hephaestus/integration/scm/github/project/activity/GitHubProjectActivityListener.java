package de.tum.cit.aet.hephaestus.integration.scm.github.project.activity;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.Project;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectRepository;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records {@code activity_event} rows for GitHub Projects v2 lifecycle events.
 *
 * <p>Lives under {@code integration/scm/github/project/activity/} because the events it
 * subscribes to ({@link GitHubProjectEvent}) and the entities it inspects ({@link Project},
 * {@link ProjectRepository}, {@link GitHubProjectEventPayload}) are GitHub-vendor types.
 * The cross-module reach into the activity write path goes through the narrow
 * {@link ActivityRecorder} SPI — the activity module owns the sole implementation
 * ({@code ActivityEventService}). No direct dependency on activity internals.
 *
 * <p>Project lifecycle is recorded for the audit trail only.
 * The actor uses webhook {@code actorId} when available, falling back to {@code creatorId}
 * for sync-replay events that lack a webhook sender.
 *
 * <p>The three helper methods ({@link #safeRecord}, {@link #hasValidScopeId},
 * {@link #getActorOrNull}) are duplicated from the original listener so this class
 * has zero coupling back to {@code activity.ActivityEventListener}. They are
 * intentionally tiny — keeping a shared abstraction would require a public utility class
 * and yield no benefit over 25 lines of trivial DRY violations.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class GitHubProjectActivityListener {

    private final ActivityRecorder activityRecorder;
    private final UserRepository userRepository;
    private final RepositoryRepository repositoryRepository;
    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;

    // Project Lifecycle Events

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectCreated(GitHubProjectEvent.ProjectCreated event) {
        var projectData = event.project();
        if (!hasValidScopeId(
                "Project created", projectData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = projectData.createdAt() != null
                ? projectData.createdAt()
                : projectData.updatedAt() != null ? projectData.updatedAt() : Instant.now();
        safeRecord(
                "project created",
                projectData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_CREATED,
                        occurredAt,
                        getActorOrNull(projectData.creatorId()),
                        getRepositoryForProject(projectData),
                        ActivityTargetType.PROJECT,
                        projectData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectUpdated(GitHubProjectEvent.ProjectUpdated event) {
        var projectData = event.project();
        if (!hasValidScopeId(
                "Project updated", projectData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = projectData.updatedAt() != null ? projectData.updatedAt() : Instant.now();
        Long actorId = projectData.actorId() != null ? projectData.actorId() : projectData.creatorId();
        safeRecord(
                "project updated",
                projectData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_UPDATED,
                        occurredAt,
                        getActorOrNull(actorId),
                        getRepositoryForProject(projectData),
                        ActivityTargetType.PROJECT,
                        projectData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectClosed(GitHubProjectEvent.ProjectClosed event) {
        var projectData = event.project();
        if (!hasValidScopeId("Project closed", projectData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = projectData.closedAt() != null
                ? projectData.closedAt()
                : projectData.updatedAt() != null ? projectData.updatedAt() : Instant.now();
        Long actorId = projectData.actorId() != null ? projectData.actorId() : projectData.creatorId();
        safeRecord(
                "project closed",
                projectData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_CLOSED,
                        occurredAt,
                        getActorOrNull(actorId),
                        getRepositoryForProject(projectData),
                        ActivityTargetType.PROJECT,
                        projectData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectReopened(GitHubProjectEvent.ProjectReopened event) {
        var projectData = event.project();
        if (!hasValidScopeId(
                "Project reopened", projectData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = projectData.updatedAt() != null ? projectData.updatedAt() : Instant.now();
        Long actorId = projectData.actorId() != null ? projectData.actorId() : projectData.creatorId();
        safeRecord(
                "project reopened",
                projectData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_REOPENED,
                        occurredAt,
                        getActorOrNull(actorId),
                        getRepositoryForProject(projectData),
                        ActivityTargetType.PROJECT,
                        projectData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectDeleted(GitHubProjectEvent.ProjectDeleted event) {
        Long projectId = event.projectId();
        if (!hasValidScopeId("Project deleted", projectId, event.context().scopeId())) {
            return;
        }
        Long scopeId = Objects.requireNonNull(event.context().scopeId());
        log.debug("Recording project deleted event: projectId={}", projectId);
        safeRecord(
                "project deleted",
                projectId,
                () -> activityRecorder.recordDeleted(
                        scopeId,
                        ActivityEventType.PROJECT_DELETED,
                        Instant.now(),
                        ActivityTargetType.PROJECT,
                        projectId));
    }

    // Project Item Events

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemCreated(GitHubProjectEvent.ProjectItemCreated event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item created", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.createdAt() != null
                ? itemData.createdAt()
                : itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        User actor = getActorOrNull(itemData.actorId());
        safeRecord(
                "project item created",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_CREATED,
                        occurredAt,
                        actor,
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemUpdated(GitHubProjectEvent.ProjectItemUpdated event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item updated", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        safeRecord(
                "project item updated",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_UPDATED,
                        occurredAt,
                        getActorOrNull(itemData.actorId()),
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemArchived(GitHubProjectEvent.ProjectItemArchived event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item archived", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        safeRecord(
                "project item archived",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_ARCHIVED,
                        occurredAt,
                        getActorOrNull(itemData.actorId()),
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemRestored(GitHubProjectEvent.ProjectItemRestored event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item restored", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        safeRecord(
                "project item restored",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_RESTORED,
                        occurredAt,
                        getActorOrNull(itemData.actorId()),
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemDeleted(GitHubProjectEvent.ProjectItemDeleted event) {
        Long itemId = event.itemId();
        if (!hasValidScopeId("Project item deleted", itemId, event.context().scopeId())) {
            return;
        }
        log.debug("Recording project item deleted event: itemId={}", itemId);
        safeRecord(
                "project item deleted",
                itemId,
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_DELETED,
                        Instant.now(),
                        null,
                        resolveRepositoryForProjectId(event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemId));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemConverted(GitHubProjectEvent.ProjectItemConverted event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item converted", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        safeRecord(
                "project item converted",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_CONVERTED,
                        occurredAt,
                        getActorOrNull(itemData.actorId()),
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectItemReordered(GitHubProjectEvent.ProjectItemReordered event) {
        var itemData = event.item();
        if (!hasValidScopeId(
                "Project item reordered", itemData.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = itemData.updatedAt() != null ? itemData.updatedAt() : Instant.now();
        safeRecord(
                "project item reordered",
                itemData.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_ITEM_REORDERED,
                        occurredAt,
                        getActorOrNull(itemData.actorId()),
                        resolveRepositoryForProjectItem(itemData, event.projectId()),
                        ActivityTargetType.PROJECT_ITEM,
                        itemData.id()));
    }

    // Project Status Update Events

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectStatusUpdateCreated(GitHubProjectEvent.ProjectStatusUpdateCreated event) {
        var data = event.statusUpdate();
        if (!hasValidScopeId(
                "Project status update created", data.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = data.createdAt() != null
                ? data.createdAt()
                : data.updatedAt() != null ? data.updatedAt() : Instant.now();
        safeRecord(
                "project status update created",
                data.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_STATUS_UPDATE_CREATED,
                        occurredAt,
                        getActorOrNull(data.creatorId()),
                        resolveRepositoryForProjectId(event.projectId()),
                        ActivityTargetType.PROJECT_STATUS_UPDATE,
                        data.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectStatusUpdateUpdated(GitHubProjectEvent.ProjectStatusUpdateUpdated event) {
        var data = event.statusUpdate();
        if (!hasValidScopeId(
                "Project status update updated", data.id(), event.context().scopeId())) {
            return;
        }
        Instant occurredAt = data.updatedAt() != null ? data.updatedAt() : Instant.now();
        safeRecord(
                "project status update updated",
                data.id(),
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_STATUS_UPDATE_UPDATED,
                        occurredAt,
                        getActorOrNull(data.creatorId()),
                        resolveRepositoryForProjectId(event.projectId()),
                        ActivityTargetType.PROJECT_STATUS_UPDATE,
                        data.id()));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectStatusUpdateDeleted(GitHubProjectEvent.ProjectStatusUpdateDeleted event) {
        Long id = event.statusUpdateId();
        if (!hasValidScopeId(
                "Project status update deleted", id, event.context().scopeId())) {
            return;
        }
        safeRecord(
                "project status update deleted",
                id,
                () -> activityRecorder.record(
                        Objects.requireNonNull(event.context().scopeId()),
                        ActivityEventType.PROJECT_STATUS_UPDATE_DELETED,
                        Instant.now(),
                        null,
                        resolveRepositoryForProjectId(event.projectId()),
                        ActivityTargetType.PROJECT_STATUS_UPDATE,
                        id));
    }

    // Helpers (mirrored from ActivityEventListener — see class javadoc)

    private void safeRecord(String eventName, Long entityId, Runnable recordAction) {
        try {
            recordAction.run();
        } catch (Exception e) {
            log.error("Failed to record activity event: eventType={}, entityId={}", eventName, entityId, e);
        }
    }

    private boolean hasValidScopeId(String eventName, Long entityId, @Nullable Long scopeId) {
        if (scopeId == null) {
            log.warn("Skipped event due to null scopeId: eventType={}, entityId={}", eventName, entityId);
            return false;
        }
        return true;
    }

    @Nullable
    private User getActorOrNull(@Nullable Long authorId) {
        if (authorId == null) {
            return null;
        }
        return userRepository.findById(authorId).orElse(null);
    }

    @Nullable
    private Repository getRepositoryForProject(GitHubProjectEventPayload.ProjectData projectData) {
        if (projectData.ownerType() == Project.OwnerType.REPOSITORY) {
            return repositoryRepository.getReferenceById(projectData.ownerId());
        }
        return null;
    }

    @Nullable
    private Repository resolveRepositoryForProjectId(@Nullable Long projectId) {
        if (projectId == null) {
            return null;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return null;
        }
        if (project.getOwnerType() == Project.OwnerType.REPOSITORY) {
            return repositoryRepository.getReferenceById(project.getOwnerId());
        }
        return null;
    }

    @Nullable
    private Repository resolveRepositoryForProjectItem(
            GitHubProjectEventPayload.ProjectItemData itemData, @Nullable Long projectId) {
        if (itemData == null) {
            return resolveRepositoryForProjectId(projectId);
        }
        if (itemData.issueId() != null) {
            var issue = issueRepository.findById(itemData.issueId()).orElse(null);
            if (issue != null && issue.getRepository() != null) {
                return repositoryRepository.getReferenceById(
                        issue.getRepository().getId());
            }
        }
        return resolveRepositoryForProjectId(projectId);
    }
}
