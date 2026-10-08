package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a later automatic push, edit or linked-work review of a pull request's author asks everything a queued
 * earlier one would. Both name the same work, author and population; the developer's current AI choice permits both;
 * the later one is routed as the workspace binds the developer now; and it can still ask every practice the earlier
 * one selects. Both selections are resolved now from one catalogue with each job's own occasion, review state and
 * rechecked practices, so a narrower occasion never covers a wider one; once the later one has captured, only the
 * practices its readiness report found ready count. Coverage is an obligation carried forward, not a result. That
 * the later one reviews the work as it stands is its caller's check: it needs the locked pull request.
 */
@Component
@ConditionalOnServerRole
public class ReplaceableReviewCoverage {

    /** Each must be present and equal: two absent identities name nobody. */
    private static final List<String> SAME_WORK_AND_AUTHOR =
            List.of("pull_request_id", PracticeCatalogInjector.AUTHOR_ID_METADATA_KEY);

    /** Each must be equal, absent on both counting as equal. */
    private static final List<String> SAME_OCCASION_FACTS = List.of(
            PracticeCatalogInjector.MERGED_BY_ID_METADATA_KEY,
            "about_user_id",
            "review_id",
            ReviewOutputService.ORIGIN_METADATA_KEY);

    private final PracticeCatalogInjector catalog;
    private final ReviewMemberAiPolicy memberAiPolicy;
    private final LlmModelResolver modelResolver;
    private final JsonMapper mapper;

    ReplaceableReviewCoverage(
            PracticeCatalogInjector catalog,
            ReviewMemberAiPolicy memberAiPolicy,
            LlmModelResolver modelResolver,
            JsonMapper mapper) {
        this.catalog = catalog;
        this.memberAiPolicy = memberAiPolicy;
        this.modelResolver = modelResolver;
        this.mapper = mapper;
    }

    public boolean covers(AgentJob later, AgentJob queued) {
        JsonNode laterFacts = later.getMetadata();
        JsonNode queuedFacts = queued.getMetadata();
        long workspaceId = queued.getWorkspace().getId();
        if (laterFacts == null
                || queuedFacts == null
                || queued.getStatus() != AgentJobStatus.QUEUED
                || ObservationAdmissionService.isAdmitted(queued)
                || !Objects.equals(
                        later.getWorkspace().getId(), queued.getWorkspace().getId())
                || !automaticAuthorReview(later)
                || !automaticAuthorReview(queued)
                || SAME_WORK_AND_AUTHOR.stream().anyMatch(key -> {
                    String fact = fact(laterFacts, key);
                    return fact == null || !fact.equals(fact(queuedFacts, key));
                })
                || SAME_OCCASION_FACTS.stream()
                        .anyMatch(key -> !Objects.equals(fact(laterFacts, key), fact(queuedFacts, key)))
                || !memberAiPolicy.permitsReview(workspaceId, AgentJobType.PULL_REQUEST_REVIEW, laterFacts)
                || !memberAiPolicy.permitsReview(workspaceId, AgentJobType.PULL_REQUEST_REVIEW, queuedFacts)) {
            return false;
        }
        Optional<Route> route = route(later.getConfigSnapshot());
        if (route.isEmpty() || !route.equals(route(queued.getConfigSnapshot())) || !route.equals(boundNow(later))) {
            return false;
        }
        Set<String> owed = selected(queued);
        Optional<Set<String>> asked = askable(later);
        return !owed.isEmpty() && asked.isPresent() && asked.get().containsAll(owed);
    }

    /**
     * What the later review can still ask: its current selection, narrowed to the practices its readiness report
     * found ready once it has captured. A report that cannot be read covers nothing.
     */
    private Optional<Set<String>> askable(AgentJob later) {
        Set<String> selected = selected(later);
        JsonNode recorded = later.getReviewReadiness();
        if (recorded == null || recorded.isNull()) return Optional.of(selected);
        try {
            Set<String> ready = mapper.treeToValue(recorded, AutomatedReviewReadinessReport.class).decisions().stream()
                    .filter(AutomatedReviewReadinessDecision::ready)
                    .map(AutomatedReviewReadinessDecision::practiceSlug)
                    .collect(Collectors.toSet());
            return Optional.of(selected.stream().filter(ready::contains).collect(Collectors.toSet()));
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    private static boolean automaticAuthorReview(AgentJob job) {
        return job.getJobType() == AgentJobType.PULL_REQUEST_REVIEW
                && job.getPurpose() == AgentPurpose.PRACTICE_REVIEW
                && job.getPracticeTriggerMode() == TriggerMode.AUTO;
    }

    /** Read as text so a number written in memory and the same number read back from JSONB compare equal. */
    private static @Nullable String fact(JsonNode metadata, String key) {
        JsonNode value = metadata.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Set<String> selected(AgentJob job) {
        try {
            return catalog.resolveEligiblePractices(job, ArtifactKinds.PULL_REQUEST).stream()
                    .map(Practice::getSlug)
                    .collect(Collectors.toSet());
        } catch (JobPreparationException nothingSelected) {
            return Set.of();
        }
    }

    /** The fields a claim revalidates against the binding, so one route is admitted or refused alike for both. */
    private record Route(
            @Nullable FundingSource scope,
            @Nullable Long connectionId,
            @Nullable Long modelId,
            @Nullable Long workspaceId,
            String upstreamModelId,
            DataHandlingTier tier) {
        static Route of(ConfigSnapshot snapshot) {
            return new Route(
                    snapshot.connectionScope(),
                    snapshot.connectionId(),
                    snapshot.modelId(),
                    snapshot.workspaceId(),
                    snapshot.upstreamModelId(),
                    Objects.requireNonNullElse(snapshot.dataHandlingTier(), DataHandlingTier.UNDECLARED));
        }
    }

    private Optional<Route> route(JsonNode snapshot) {
        try {
            return Optional.of(Route.of(ConfigSnapshot.fromJson(snapshot, mapper)));
        } catch (IllegalArgumentException | IllegalStateException | JacksonException unreadable) {
            return Optional.empty();
        }
    }

    /** The route the workspace binds the developer to now, read from the database only. */
    private Optional<Route> boundNow(AgentJob job) {
        try {
            return memberAiPolicy
                    .binding(job.getWorkspace().getId(), job.getJobType(), job.getMetadata())
                    .map(binding -> Route.of(ConfigSnapshot.from(binding, modelResolver)));
        } catch (IllegalStateException unavailable) {
            return Optional.empty();
        }
    }
}
