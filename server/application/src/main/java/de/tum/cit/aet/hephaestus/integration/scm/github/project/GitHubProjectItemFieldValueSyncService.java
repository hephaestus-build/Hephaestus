package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.FIELD_VALUES_PAGINATION_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ExponentialBackoff;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectFieldDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectFieldValueDTO;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubProjectItemFieldValueSyncService {

    private static final String GET_PROJECT_ITEM_FIELD_VALUES_DOCUMENT = "GetProjectItemFieldValues";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final ProjectFieldRepository projectFieldRepository;
    private final ProjectFieldValueRepository projectFieldValueRepository;
    private final ProjectItemRepository projectItemRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubSyncProperties syncProperties;
    private final TransactionTemplate transactionTemplate;
    private final GitHubExceptionClassifier exceptionClassifier;

    @Transactional
    public List<String> processFieldValues(
            @Nullable Long itemId,
            @Nullable List<GitHubProjectFieldValueDTO> fieldValues,
            boolean truncated,
            @Nullable String endCursor) {
        if (itemId == null) {
            return List.of();
        }

        if (fieldValues == null || fieldValues.isEmpty()) {
            if (!truncated) {
                deleteAllFieldValues(itemId);
            }
            return List.of();
        }

        List<String> processedFieldIds = new ArrayList<>();

        for (GitHubProjectFieldValueDTO fieldValue : fieldValues) {
            if (fieldValue == null || fieldValue.fieldId() == null) {
                continue;
            }

            if (!projectFieldRepository.existsById(fieldValue.fieldId())) {
                log.debug(
                        "Skipped field value: reason=fieldNotFound, fieldId={}, itemId={}",
                        fieldValue.fieldId(),
                        itemId);
                continue;
            }

            processedFieldIds.add(fieldValue.fieldId());

            projectFieldValueRepository.upsertCore(
                    itemId,
                    fieldValue.fieldId(),
                    fieldValue.textValue(),
                    fieldValue.numberValue(),
                    fieldValue.dateValue(),
                    fieldValue.singleSelectOptionId(),
                    fieldValue.iterationId(),
                    Instant.now());
        }

        if (!truncated) {
            removeStaleFieldValues(itemId, processedFieldIds);
        }

        return processedFieldIds;
    }

    public int syncRemainingFieldValues(
            Long scopeId,
            @Nullable String itemNodeId,
            @Nullable Long itemId,
            @Nullable String startCursor,
            List<String> initialFieldIds) {
        if (itemNodeId == null || itemNodeId.isBlank() || itemId == null) {
            return 0;
        }

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        Duration timeout = syncProperties.graphqlTimeout();

        boolean hasMore = true;
        String cursor = startCursor;
        int pageCount = 0;
        int totalSynced = 0;
        int reportedTotalCount = -1;
        boolean completedNormally = false;
        List<String> processedFieldIds = new ArrayList<>();
        int retryAttempt = 0;
        if (initialFieldIds != null) {
            processedFieldIds.addAll(initialFieldIds);
        }

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for item field values: itemId={}, limit={}",
                        itemId,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(
                                () -> client.documentName(GET_PROJECT_ITEM_FIELD_VALUES_DOCUMENT)
                                        .variable("itemId", itemNodeId)
                                        .variable(
                                                "first",
                                                adaptPageSize(
                                                        FIELD_VALUES_PAGINATION_SIZE,
                                                        graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                        .variable("after", currentCursor)
                                        .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying field values pagination after transport error: itemId={}, page={}, attempt={}, error={}",
                                        itemId,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(timeout);

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = classifyGraphQlErrors(graphQlResponse);
                    if (classification != null) {
                        if (handleClassification(
                                classification, "field values pagination", "itemId", itemId, retryAttempt)) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response for item field values: itemId={}, errors={}",
                            itemId,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "field values pagination", "itemId", itemId)) {
                        break;
                    }
                }

                GHProjectV2ItemFieldValueConnection fieldValuesConnection =
                        graphQlResponse.field("node.fieldValues").toEntity(GHProjectV2ItemFieldValueConnection.class);

                if (fieldValuesConnection == null || fieldValuesConnection.getNodes() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = fieldValuesConnection.getTotalCount();
                }

                List<GitHubProjectFieldValueDTO> fieldValueDTOs = fieldValuesConnection.getNodes().stream()
                        .map(GitHubProjectFieldValueDTO::fromFieldValue)
                        .filter(dto -> dto != null && dto.fieldId() != null)
                        .toList();

                transactionTemplate.executeWithoutResult(status -> {
                    if (!projectItemRepository.existsById(itemId)) {
                        log.debug("Skipped field values: reason=itemNotFound, itemId={}", itemId);
                        return;
                    }

                    for (GitHubProjectFieldValueDTO fieldValue : fieldValueDTOs) {
                        String fieldId = fieldValue.fieldId();
                        if (fieldId == null) {
                            continue;
                        }
                        if (!projectFieldRepository.existsById(fieldId)) {
                            log.debug(
                                    "Skipped field value: reason=fieldNotFound, fieldId={}, itemId={}",
                                    fieldValue.fieldId(),
                                    itemId);
                            continue;
                        }

                        processedFieldIds.add(fieldId);

                        projectFieldValueRepository.upsertCore(
                                itemId,
                                fieldId,
                                fieldValue.textValue(),
                                fieldValue.numberValue(),
                                fieldValue.dateValue(),
                                fieldValue.singleSelectOptionId(),
                                fieldValue.iterationId(),
                                Instant.now());
                    }
                });

                totalSynced += fieldValuesConnection.getNodes().size();

                var pageInfo = fieldValuesConnection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                if (!hasMore) {
                    completedNormally = true;
                }

                if (fieldValuesConnection.getNodes().isEmpty()) {
                    completedNormally = true;
                    break;
                }

                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Field values pagination interrupted during throttle: itemId={}", itemId);
                        break;
                    }
                }
                retryAttempt = 0;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (!handleClassification(classification, "field values pagination", "itemId", itemId, retryAttempt)) {
                    break;
                }
                retryAttempt++;
            }
        }

        // totalSynced is the raw node count; completedNormally is false only on a page-cap/error stop.
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "fieldValues", totalSynced, reportedTotalCount, !completedNormally, "itemId=" + itemNodeId);
        }

        if (!processedFieldIds.isEmpty() && completedNormally) {
            removeStaleFieldValues(itemId, processedFieldIds);
        }

        if (totalSynced > 0) {
            log.debug("Synced remaining field values: itemId={}, count={}, pages={}", itemId, totalSynced, pageCount);
        }

        return totalSynced;
    }

    /**
     * Upserts a project field definition from a DTO.
     * <p>
     * Delegates to {@link ProjectFieldRepository#upsertCore} so callers do not need
     * a direct dependency on the field repository.
     *
     * @param fieldDto  the field DTO containing id, name, data type, options, and timestamps
     * @param projectId the owning project's database ID
     */
    @Transactional
    public void upsertFieldDefinition(GitHubProjectFieldDTO fieldDto, Long projectId) {
        ProjectField.DataType dataType = fieldDto.getDataTypeEnum();
        if (dataType == null) {
            dataType = ProjectField.DataType.TEXT;
        }
        Instant createdAt = fieldDto.createdAt() != null ? fieldDto.createdAt() : Instant.now();
        Instant updatedAt = fieldDto.updatedAt() != null ? fieldDto.updatedAt() : Instant.now();

        projectFieldRepository.upsertCore(
                fieldDto.id(),
                projectId,
                fieldDto.name(),
                dataType.name(),
                fieldDto.getOptionsJson(),
                createdAt,
                updatedAt);
    }

    /**
     * Removes project field definitions that were not seen during the current sync cycle.
     *
     * @param projectId      the project whose fields to clean up
     * @param syncedFieldIds IDs of fields that were synced (should be kept)
     * @return the number of stale fields removed
     */
    @Transactional
    public int removeStaleFieldDefinitions(Long projectId, Collection<String> syncedFieldIds) {
        return projectFieldRepository.deleteByProjectIdAndIdNotIn(projectId, List.copyOf(syncedFieldIds));
    }

    private void deleteAllFieldValues(Long itemId) {
        int removed = projectFieldValueRepository.deleteAllByItemId(itemId);
        if (removed > 0) {
            log.debug("Removed field values: itemId={}, count={}", itemId, removed);
        }
    }

    private void removeStaleFieldValues(Long itemId, List<String> processedFieldIds) {
        if (processedFieldIds == null || processedFieldIds.isEmpty()) {
            deleteAllFieldValues(itemId);
            return;
        }

        int removed = projectFieldValueRepository.deleteByItemIdAndFieldIdNotIn(itemId, processedFieldIds);
        if (removed > 0) {
            log.debug("Removed stale field values: itemId={}, count={}", itemId, removed);
        }
    }

    private @Nullable ClassificationResult classifyGraphQlErrors(@Nullable ClientGraphQlResponse response) {
        ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(response);
        if (classification != null) {
            return classification;
        }

        GitHubGraphQlErrorUtils.TransientError transientError = GitHubGraphQlErrorUtils.detectTransientError(response);
        if (transientError == null) {
            return null;
        }

        return switch (transientError.type()) {
            case RATE_LIMIT ->
                ClassificationResult.rateLimited(
                        transientError.getRecommendedWait(), "GraphQL rate limit: " + transientError.message());
            case TIMEOUT, SERVER_ERROR ->
                ClassificationResult.of(Category.RETRYABLE, "GraphQL transient error: " + transientError.message());
            case RESOURCE_LIMIT ->
                ClassificationResult.of(Category.CLIENT_ERROR, "GraphQL resource limit: " + transientError.message());
        };
    }

    private boolean handleClassification(
            ClassificationResult classification, String phase, String scopeLabel, Object scopeValue, int retryAttempt) {
        Category category = classification.category();

        return switch (category) {
            case RETRYABLE -> {
                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                            "Retrying {} after transient error: {}={}, attempt={}, error={}",
                            phase,
                            scopeLabel,
                            scopeValue,
                            retryAttempt + 1,
                            classification.message());
                    try {
                        ExponentialBackoff.sleep(retryAttempt + 1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        yield false;
                    }
                    yield true;
                }
                log.warn(
                        "Aborting {} after {} retries: {}={}, error={}",
                        phase,
                        MAX_RETRY_ATTEMPTS,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case RATE_LIMITED -> {
                if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                    long waitMs = Math.min(classification.suggestedWait().toMillis(), 300_000);
                    log.warn(
                            "Rate limited during {}, waiting: {}={}, waitMs={}", phase, scopeLabel, scopeValue, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        yield false;
                    }
                    yield true;
                }
                log.warn(
                        "Aborting {} due to rate limit: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case NOT_FOUND -> {
                log.warn(
                        "Resource not found during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case AUTH_ERROR -> {
                log.warn(
                        "Authentication error during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            case CLIENT_ERROR -> {
                log.warn(
                        "Client error during {}: {}={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        classification.message());
                yield false;
            }
            default -> {
                log.warn(
                        "Aborting {} due to error: {}={}, category={}, error={}",
                        phase,
                        scopeLabel,
                        scopeValue,
                        category,
                        classification.message());
                yield false;
            }
        };
    }

    private boolean waitForRateLimitIfNeeded(Long scopeId, String phase, String scopeLabel, Object scopeValue) {
        try {
            boolean waited = graphQlClientProvider.waitIfRateLimitLow(scopeId);
            if (waited) {
                log.info("Paused due to critical rate limit: phase={}, {}={}", phase, scopeLabel, scopeValue);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for rate limit reset: phase={}, {}={}", phase, scopeLabel, scopeValue);
            return false;
        }
    }
}
