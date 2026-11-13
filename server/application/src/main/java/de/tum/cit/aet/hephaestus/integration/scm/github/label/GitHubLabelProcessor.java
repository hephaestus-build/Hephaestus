package de.tum.cit.aet.hephaestus.integration.scm.github.label;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.LabelIdUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub labels.
 * <p>
 * This service handles the conversion of GitHubLabelDTO to Label entities,
 * persists them, and publishes appropriate domain events.
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via upsert pattern</li>
 * <li>Domain events published for reactive feature development</li>
 * <li>Works exclusively with DTOs for complete field coverage</li>
 * </ul>
 * <p>
 * <b>Note on Label IDs:</b>
 * GitHub's GraphQL API does not expose databaseId for labels, only node_id.
 * This processor supports both ID-based lookup (for webhook events which provide databaseId)
 * and name-based lookup (for GraphQL sync where only name is reliably available).
 */
@Service
public class GitHubLabelProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubLabelProcessor.class);

    private final LabelRepository labelRepository;
    private final IdentityProviderRepository gitProviderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubLabelProcessor(
            LabelRepository labelRepository,
            IdentityProviderRepository gitProviderRepository,
            ApplicationEventPublisher eventPublisher) {
        this.labelRepository = labelRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub label DTO and persist it as a Label entity.
     * Uses ID-based lookup when id is available (webhooks), otherwise falls back to name-based lookup.
     * Publishes appropriate domain events based on what changed.
     * <p>
     * For new labels from GraphQL (which doesn't provide databaseId), a deterministic ID is generated
     * from the repository ID and label name to ensure consistency.
     *
     * @param dto the GitHub label DTO
     * @param repository the repository this label belongs to
     * @param context processing context with scope information
     * @return the persisted Label entity
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable Label process(@Nullable GitHubLabelDTO dto, Repository repository, ProcessingContext context) {
        if (dto == null || dto.name() == null) {
            log.warn(
                    "Skipped label processing: reason=nullOrMissingName, repoId={}",
                    repository != null ? repository.getId() : null);
            return null;
        }

        // Always check by unique key (repository_id + name) FIRST - this is the constraint we
        // enforce.
        // This handles the case where GraphQL sync created a label with a deterministic nativeId,
        // then provider inbox replay sends a webhook with the actual GitHub databaseId.
        Optional<Label> existingOpt = labelRepository.findByRepositoryIdAndName(repository.getId(), dto.name());

        // Fall back to nativeId lookup if name lookup didn't find it (handles label renames)
        if (existingOpt.isEmpty() && dto.id() != null) {
            existingOpt =
                    labelRepository.findByNativeIdAndProviderId(dto.id(), Objects.requireNonNull(context.providerId()));
        }
        boolean isNew = existingOpt.isEmpty();

        Label label = existingOpt.orElseGet(Label::new);

        // Hibernate forbids changing a managed entity's nativeId/provider identifier.
        if (isNew) {
            long nativeId = dto.id() != null ? dto.id() : generateDeterministicId(repository.getId(), dto.name());
            label.setNativeId(nativeId);
            // CRITICAL: Use getReferenceById to get a proxy bound to the current session.
            // context.provider() returns a proxy from the outer session (REQUIRES_NEW suspends it),
            // which causes "Illegally attempted to associate proxy with two open sessions".
            label.setProvider(gitProviderRepository.getReferenceById(Objects.requireNonNull(context.providerId())));
        }
        label.setName(dto.name()); // name is always required
        if (dto.color() != null) {
            label.setColor(dto.color());
        }
        label.setDescription(dto.description()); // labels allow clearing description to null
        label.setRepository(repository);

        // Set GitHub timestamps (nullable - only available from GraphQL, not webhooks)
        if (dto.createdAt() != null) {
            label.setCreatedAt(dto.createdAt());
        }
        if (dto.updatedAt() != null) {
            label.setUpdatedAt(dto.updatedAt());
        }

        // Mark sync timestamp
        label.setLastSyncAt(Instant.now());

        Label saved = labelRepository.save(label);

        // Publish domain event with DTO payload (safe for async handling)
        ScmEventPayload.LabelData labelData = ScmEventPayload.LabelData.from(saved);
        EventContext eventContext = EventContext.from(context);
        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.LabelCreated(labelData, eventContext));
            log.debug("Created label: labelId={}, labelName={}", saved.getId(), saved.getName());
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.LabelUpdated(labelData, eventContext));
            log.debug("Updated label: labelId={}, labelName={}", saved.getId(), saved.getName());
        }

        return saved;
    }

    private Long generateDeterministicId(Long repositoryId, String labelName) {
        return LabelIdUtils.generateDeterministicId(repositoryId, labelName);
    }

    /**
     * Delete a label by its database ID.
     * <p>
     * IMPORTANT: For bidirectional @ManyToMany relationships,
     * we must sync both sides of the relationship before deleting to avoid
     * constraint violations or stale references in the persistence context.
     *
     * @param labelId the label database ID (synthetic PK)
     * @param context processing context with scope information
     */
    @Transactional
    public void delete(@Nullable Long labelId, ProcessingContext context) {
        if (labelId == null) {
            return;
        }

        labelRepository.findById(labelId).ifPresent(label -> deleteLabel(label, context));
    }

    /**
     * Delete a label by its native ID (provider-assigned ID).
     * Used by webhook handlers where only the native ID is available.
     *
     * @param nativeId the label's native ID from the provider
     * @param context processing context with scope information
     */
    @Transactional
    public void deleteByNativeId(Long nativeId, ProcessingContext context) {
        if (nativeId == null) {
            return;
        }

        labelRepository
                .findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(context.providerId()))
                .ifPresent(label -> deleteLabel(label, context));
    }

    private void deleteLabel(Label label, ProcessingContext context) {
        // CRITICAL: Remove from all referencing Issues before deletion
        // to sync bidirectional ManyToMany relationship
        label.removeAllIssues();

        labelRepository.delete(label);
        eventPublisher.publishEvent(
                new ScmDomainEvent.LabelDeleted(label.getId(), label.getName(), EventContext.from(context)));
        log.info("Deleted label: labelId={}, labelName={}", label.getId(), label.getName());
    }
}
