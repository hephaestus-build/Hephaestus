package de.tum.cit.aet.hephaestus.integration.scm.github.milestone;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
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
 * Processor for GitHub milestones.
 * <p>
 * This service handles the conversion of GitHubMilestoneDTO to Milestone entities,
 * persists them, and publishes appropriate domain events.
 * <p>
 * <b>Note on Milestone IDs:</b>
 * GitHub's GraphQL API does not expose databaseId for milestones, while webhooks do.
 * To avoid duplicates, this processor always uses number-based lookup within a repository
 * (milestone numbers are unique per repository). When the real GitHub databaseId is available
 * from webhooks, it updates the entity ID accordingly.
 */
@Service
public class GitHubMilestoneProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubMilestoneProcessor.class);

    private final MilestoneRepository milestoneRepository;
    private final IssueRepository issueRepository;
    private final GitHubUserProcessor gitHubUserProcessor;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubMilestoneProcessor(
            MilestoneRepository milestoneRepository,
            IssueRepository issueRepository,
            GitHubUserProcessor gitHubUserProcessor,
            ApplicationEventPublisher eventPublisher) {
        this.milestoneRepository = milestoneRepository;
        this.issueRepository = issueRepository;
        this.gitHubUserProcessor = gitHubUserProcessor;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub milestone DTO and persist it as a Milestone entity.
     * <p>
     * <b>IMPORTANT:</b> Always uses number-based lookup within the repository to find existing milestones.
     * This ensures that milestones created via webhooks (with real GitHub databaseId) and milestones
     * synced via GraphQL (which doesn't provide databaseId) are treated as the same entity.
     * <p>
     * When the real GitHub databaseId is available (from webhooks), it will be used as the entity ID
     * and will replace any previously generated deterministic ID.
     *
     * @param dto the GitHub milestone DTO
     * @param repository the repository this milestone belongs to
     * @param creatorDto optional creator user DTO
     * @param context processing context with scope information
     * @return the persisted Milestone entity
     */
    @Transactional
    public @Nullable Milestone process(
            @Nullable GitHubMilestoneDTO dto,
            Repository repository,
            @Nullable GitHubUserDTO creatorDto,
            ProcessingContext context) {
        if (dto == null) {
            log.warn(
                    "Skipped milestone processing: reason=nullDto, repoId={}",
                    repository != null ? repository.getId() : null);
            return null;
        }

        if (dto.number() <= 0) {
            log.warn("Skipped milestone processing: reason=invalidNumber, repoId={}", repository.getId());
            return null;
        }

        // Always look up by number within the repository to avoid duplicates.
        // The milestone number is unique within a repository and is the canonical identifier.
        Optional<Milestone> existingOpt =
                milestoneRepository.findByNumberAndRepositoryId(dto.number(), repository.getId());
        boolean isNew = existingOpt.isEmpty();

        Milestone milestone = existingOpt.orElseGet(Milestone::new);

        // Handle nativeId assignment:
        // - If we have the real GitHub databaseId (from webhooks), use it as nativeId
        // - If existing milestone has a different nativeId (e.g., generated deterministic value),
        //   update it in place (PK is auto-generated, so no migration needed)
        // - For new milestones without databaseId (GraphQL), generate a deterministic nativeId
        if (dto.id() != null) {
            // We have the real GitHub databaseId - use it as nativeId
            milestone.setNativeId(dto.id());
        } else if (isNew) {
            // New milestone from GraphQL (no databaseId) - generate deterministic nativeId
            milestone.setNativeId(generateDeterministicId(repository.getId(), dto.number()));
        }
        // else: existing milestone, keep its current nativeId (whether real or generated)

        // Set provider for new milestones
        if (isNew) {
            milestone.setProvider(Objects.requireNonNull(context.provider()));
        }
        if (dto.number() > 0) {
            milestone.setNumber(dto.number());
        }
        if (dto.title() != null) {
            milestone.setTitle(dto.title());
        }
        if (isNew || dto.description() != null) {
            milestone.setDescription(dto.description());
        }
        if (dto.htmlUrl() != null) {
            milestone.setHtmlUrl(dto.htmlUrl());
        }
        if (isNew || dto.dueOn() != null) {
            milestone.setDueOn(dto.dueOn());
        }
        if (dto.state() != null) {
            milestone.setState(parseState(dto.state()));
        } else if (isNew) {
            // Default to OPEN for new milestones without state
            milestone.setState(Milestone.State.OPEN);
        }
        // Set issue counts if provided
        if (dto.openIssuesCount() != null) {
            milestone.setOpenIssuesCount(dto.openIssuesCount());
        }
        if (dto.closedIssuesCount() != null) {
            milestone.setClosedIssuesCount(dto.closedIssuesCount());
        }
        // Set timestamps if provided
        if (dto.createdAt() != null) {
            milestone.setCreatedAt(dto.createdAt());
        }
        if (dto.updatedAt() != null) {
            milestone.setUpdatedAt(dto.updatedAt());
        }
        if (dto.closedAt() != null) {
            milestone.setClosedAt(dto.closedAt());
        }
        milestone.setRepository(repository);

        // Set creator if provided
        if (creatorDto != null) {
            User creator = findOrCreateUser(creatorDto, Objects.requireNonNull(context.providerId()));
            milestone.setCreator(creator);
        }

        // Mark sync timestamp
        milestone.setLastSyncAt(Instant.now());

        Milestone saved = milestoneRepository.save(milestone);

        // Publish domain event with ScmEventPayload DTO
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
     * Generates a deterministic ID for milestones synced via GraphQL.
     * GitHub's GraphQL API doesn't expose databaseId for milestones, so we generate
     * a consistent ID based on the repository ID and milestone number.
     * <p>
     * The generated ID is negative to avoid collision with actual GitHub databaseIds.
     *
     * @param repositoryId the repository's database ID
     * @param milestoneNumber the milestone's number
     * @return a deterministic negative Long ID
     */
    private Long generateDeterministicId(Long repositoryId, int milestoneNumber) {
        // Use bit shifting to combine repo ID and milestone number without collision.
        // The formula repositoryId * 31 + milestoneNumber can produce collisions
        // (e.g., repo=2,milestone=31 and repo=3,milestone=0 both = 62).
        // Bit shifting separates components: repo ID in upper 32 bits, milestone number in lower 32 bits.
        // Negative IDs won't collide with GitHub's positive databaseIds.
        long combined = (repositoryId << 32) | (milestoneNumber & 0xFFFFFFFFL);
        return -combined;
    }

    /**
     * Delete a milestone by its ID.
     * <p>
     * IMPORTANT: Nullifies the milestone reference on all associated issues
     * before deletion to maintain data integrity and prevent orphaned references.
     * Uses direct database UPDATE to ensure ALL references are cleared, not just
     * those loaded in the in-memory collection.
     *
     * @param nativeId the milestone's native provider ID
     * @param context processing context with scope information
     */
    @Transactional
    public void delete(@Nullable Long nativeId, ProcessingContext context) {
        if (nativeId == null) {
            return;
        }

        milestoneRepository
                .findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(context.providerId()))
                .ifPresent(milestone -> {
                    Long milestoneId = milestone.getId();
                    // Clean up issue references via direct database UPDATE before deletion.
                    // Using issueRepository.clearMilestoneReferences() instead of milestone.removeAllIssues()
                    // because the in-memory collection may be stale or not fully loaded.
                    int clearedCount = issueRepository.clearMilestoneReferences(milestoneId);
                    if (clearedCount > 0) {
                        log.debug("Cleared milestone references from {} issues before deletion", clearedCount);
                    }

                    milestoneRepository.delete(milestone);
                    eventPublisher.publishEvent(new ScmDomainEvent.MilestoneDeleted(
                            milestoneId, milestone.getTitle(), EventContext.from(context)));
                    log.info(
                            "Deleted milestone: milestoneId={}, milestoneNumber={}",
                            milestoneId,
                            milestone.getNumber());
                });
    }

    /**
     * Converts a GitHub API milestone state string to Milestone.State enum.
     */
    private Milestone.State parseState(String state) {
        if (state == null) {
            log.warn(
                    "Milestone state is null, defaulting to OPEN. This may indicate missing data in webhook or GraphQL response.");
            return Milestone.State.OPEN;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> Milestone.State.OPEN;
            case "CLOSED" -> Milestone.State.CLOSED;
            default -> {
                log.warn("Unknown milestone state '{}', defaulting to OPEN", state);
                yield Milestone.State.OPEN;
            }
        };
    }

    @Nullable
    private User findOrCreateUser(GitHubUserDTO dto, Long providerId) {
        return gitHubUserProcessor.findOrCreate(dto, providerId);
    }
}
