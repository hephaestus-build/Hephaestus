package de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab milestones.
 * <p>
 * Converts {@link GitLabMilestoneDTO} to {@link Milestone} entities, persists them,
 * and publishes domain events. Extends {@link BaseGitLabProcessor} for shared
 * utilities (timestamp parsing, sanitization, GitLab properties).
 * <p>
 * <b>State mapping:</b>
 * <ul>
 *   <li>GitLab {@code "active"} &rarr; {@link Milestone.State#OPEN}</li>
 *   <li>GitLab {@code "closed"} &rarr; {@link Milestone.State#CLOSED}</li>
 * </ul>
 * <p>
 * <b>Group milestone handling:</b>
 * Group milestones share a single global ID across all projects. To avoid
 * {@code (provider_id, native_id)} constraint violations, group milestones use
 * a deterministic negative nativeId based on (repositoryId, iid).
 */
@Service
public class GitLabMilestoneProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabMilestoneProcessor.class);

    private final MilestoneRepository milestoneRepository;
    private final IssueRepository issueRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabMilestoneProcessor(
            GitLabUserService gitLabUserService,
            UserRepository userRepository,
            LabelRepository labelRepository,
            RepositoryRepository repositoryRepository,
            ScopeIdResolver scopeIdResolver,
            RepositoryScopeFilter repositoryScopeFilter,
            GitLabProperties gitLabProperties,
            MilestoneRepository milestoneRepository,
            IssueRepository issueRepository,
            ApplicationEventPublisher eventPublisher) {
        super(
                gitLabUserService,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                gitLabProperties);
        this.milestoneRepository = milestoneRepository;
        this.issueRepository = issueRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Processes a GitLab milestone DTO and persists it as a Milestone entity.
     * <p>
     * Lookup is always by (iid, repository_id) — the canonical unique key.
     *
     * @param dto        the GitLab milestone DTO
     * @param repository the repository this milestone belongs to
     * @param context    processing context with scope information
     * @return the persisted Milestone entity, or null if the DTO is invalid
     */
    @Transactional
    @Nullable
    public Milestone process(@Nullable GitLabMilestoneDTO dto, Repository repository, ProcessingContext context) {
        if (dto == null) {
            log.warn(
                    "Skipped milestone processing: reason=nullDto, repoId={}",
                    repository != null ? repository.getId() : null);
            return null;
        }

        if (dto.iid() <= 0) {
            log.warn("Skipped milestone processing: reason=invalidIid, repoId={}", repository.getId());
            return null;
        }

        Optional<Milestone> existingOpt =
                milestoneRepository.findByNumberAndRepositoryId(dto.iid(), repository.getId());
        boolean isNew = existingOpt.isEmpty();

        Milestone milestone = existingOpt.orElseGet(Milestone::new);

        // Handle nativeId: group milestones use deterministic ID to avoid (provider_id, native_id) collisions
        if (isNew) {
            if (dto.groupMilestone()) {
                milestone.setNativeId(generateDeterministicMilestoneId(repository.getId(), dto.iid()));
            } else {
                milestone.setNativeId(dto.nativeId());
            }
            milestone.setProvider(Objects.requireNonNull(context.provider()));
        } else if (!dto.groupMilestone() && milestone.getNativeId() < 0) {
            // Existing milestone was stored with deterministic ID but now has real ID
            milestone.setNativeId(dto.nativeId());
        }

        milestone.setNumber(dto.iid());

        if (dto.title() != null) {
            milestone.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), ""));
        }
        if (isNew || dto.description() != null) {
            milestone.setDescription(sanitize(dto.description()));
        }

        // Construct htmlUrl
        String htmlUrl = buildHtmlUrl(dto, repository);
        if (htmlUrl != null) {
            milestone.setHtmlUrl(htmlUrl);
        } else if (isNew) {
            milestone.setHtmlUrl(""); // @NonNull field
        }

        // State mapping
        if (dto.state() != null) {
            Milestone.State newState = parseState(dto.state());
            Milestone.State oldState = milestone.getState();
            milestone.setState(newState);

            // Approximate closedAt: set on close, clear on reopen
            if (newState == Milestone.State.CLOSED && oldState != Milestone.State.CLOSED) {
                Instant updatedAt = parseGitLabTimestamp(dto.updatedAt());
                milestone.setClosedAt(updatedAt != null ? updatedAt : Instant.now());
            } else if (newState == Milestone.State.OPEN && oldState == Milestone.State.CLOSED) {
                milestone.setClosedAt(null);
            }
        } else if (isNew) {
            milestone.setState(Milestone.State.OPEN);
        }

        // Due date parsing
        if (dto.dueDate() != null) {
            milestone.setDueOn(parseDateToInstant(dto.dueDate()));
        } else if (isNew) {
            milestone.setDueOn(null);
        }

        // Issue counts (only from GraphQL, not webhooks)
        if (dto.totalIssuesCount() != null && dto.closedIssuesCount() != null) {
            milestone.setOpenIssuesCount(dto.totalIssuesCount() - dto.closedIssuesCount());
            milestone.setClosedIssuesCount(dto.closedIssuesCount());
        }

        // Timestamps
        if (dto.createdAt() != null) {
            milestone.setCreatedAt(parseGitLabTimestamp(dto.createdAt()));
        }
        if (dto.updatedAt() != null) {
            milestone.setUpdatedAt(parseGitLabTimestamp(dto.updatedAt()));
        }

        milestone.setRepository(repository);
        milestone.setLastSyncAt(Instant.now());

        Milestone saved = milestoneRepository.save(milestone);

        ScmEventPayload.MilestoneData milestoneData = ScmEventPayload.MilestoneData.from(saved);
        EventContext eventContext = EventContext.from(context);
        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.MilestoneCreated(milestoneData, eventContext));
            log.debug("Created milestone: milestoneId={}, milestoneNumber={}", saved.getId(), saved.getNumber());
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.MilestoneUpdated(milestoneData, eventContext));
            log.debug("Updated milestone: milestoneId={}, milestoneNumber={}", saved.getId(), saved.getNumber());
        }

        return saved;
    }

    /**
     * Deletes a milestone by its number and repository, cleaning up issue references first.
     *
     * @param milestoneId the milestone database PK
     * @param context     processing context with scope information
     */
    @Transactional
    public void delete(@Nullable Long milestoneId, ProcessingContext context) {
        if (milestoneId == null) {
            return;
        }

        milestoneRepository.findById(milestoneId).ifPresent(milestone -> {
            int clearedCount = issueRepository.clearMilestoneReferences(milestoneId);
            if (clearedCount > 0) {
                log.debug("Cleared milestone references from {} issues before deletion", clearedCount);
            }

            milestoneRepository.delete(milestone);
            eventPublisher.publishEvent(
                    new ScmDomainEvent.MilestoneDeleted(milestoneId, milestone.getTitle(), EventContext.from(context)));
            log.info("Deleted milestone: milestoneId={}, milestoneNumber={}", milestoneId, milestone.getNumber());
        });
    }

    /**
     * Maps GitLab milestone state to the Milestone.State enum.
     * <p>
     * GitLab uses {@code "active"}/{@code "closed"} (different from GitHub's {@code "open"}/{@code "closed"}).
     */
    static Milestone.State parseState(String state) {
        if (state == null) {
            return Milestone.State.OPEN;
        }
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "active" -> Milestone.State.OPEN;
            case "closed" -> Milestone.State.CLOSED;
            default -> {
                log.warn("Unknown GitLab milestone state '{}', defaulting to OPEN", state);
                yield Milestone.State.OPEN;
            }
        };
    }

    /**
     * Generates a deterministic negative nativeId for group milestones.
     * <p>
     * Group milestones share a single global ID across all projects in the group.
     * Using the real global ID would cause {@code (provider_id, native_id)} constraint
     * violations when the same group milestone is stored for multiple projects.
     */
    private static long generateDeterministicMilestoneId(long repositoryId, int iid) {
        long combined = (repositoryId << 32) | (iid & 0xFFFFFFFFL);
        return -combined;
    }

    @Nullable
    private String buildHtmlUrl(GitLabMilestoneDTO dto, Repository repository) {
        if (dto.webPath() != null && !dto.webPath().isBlank()) {
            // GraphQL path: serverUrl + webPath
            return gitLabProperties.defaultServerUrl() + dto.webPath();
        }
        if (dto.projectWebUrl() != null && !dto.projectWebUrl().isBlank()) {
            // Webhook path: project.web_url + "/-/milestones/" + iid
            return dto.projectWebUrl() + "/-/milestones/" + dto.iid();
        }
        // Fallback: construct from repository nameWithOwner
        if (repository.getNameWithOwner() != null) {
            return (gitLabProperties.defaultServerUrl() + "/" + repository.getNameWithOwner() + "/-/milestones/"
                    + dto.iid());
        }
        return null;
    }

    /**
     * Parses a date string to an Instant at midnight UTC.
     * <p>
     * Handles both date-only strings ({@code "2026-06-01"}) and full ISO-8601
     * timestamps ({@code "2026-06-01T00:00:00Z"}) for resilience against
     * GitLab API format changes.
     */
    @Nullable
    static Instant parseDateToInstant(@Nullable String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        // Try date-only format first (GitLab's current format for dueDate)
        try {
            return LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            // Fall back to full timestamp parsing
            try {
                return Instant.parse(dateStr);
            } catch (DateTimeParseException e2) {
                log.warn("Could not parse date: value={}", dateStr);
                return null;
            }
        }
    }
}
