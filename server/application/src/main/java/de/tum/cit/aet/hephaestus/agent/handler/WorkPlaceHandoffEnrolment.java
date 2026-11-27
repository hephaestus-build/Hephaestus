package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.composer.WorkPlaceScope;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkPlaceHandoffRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkPlaceHandoffRow;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSealed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The seal's handoff to the work place of a pull or merge request (ADR 0121): in the seal's transaction, once per
 * review, it writes the scoped copy that the work place's composer may read. The legacy {@code REVIEW_COMPOSE} run stays
 * the producer of every copy on the work; the handoff records that, and nothing reads it to post.
 *
 * <p>It listens to {@link ReviewSealed} in the seal's own transaction, so the handoff commits with the seal or not at
 * all. It reads what the seal just admitted and the inputs that the legacy composition would compose from, never the
 * private read: the developer's earlier private feedback, page support and conversation routing stay out, so the
 * handoff is the same whatever they hold. An issue has no work place in 1.0, and a chat thread or a document gets no
 * copy on the work.
 *
 * <p>Only the database can fail the seal here (#5306). A retained input that does not decode, or a reference that is
 * missing, records a failed handoff and the seal commits without one.
 */
@Component
@ConditionalOnServerRole
public class WorkPlaceHandoffEnrolment {
    private static final Logger log = LoggerFactory.getLogger(WorkPlaceHandoffEnrolment.class);

    /** The one item kind with a work place in 1.0: a GitHub pull request or a GitLab merge request. */
    static final ItemKind PULL_REQUEST = new ItemKind("scm.pull_request");

    /** What the seal did with its handoff: the {@code outcome} and {@code reason} of its counter. */
    enum Decision {
        WRITTEN("written", "none"),
        /** A backfill, or an item with no work place in 1.0. A trial publishes no seal. */
        INELIGIBLE("skipped", "ineligible_review"),
        /** The item takes no copy on the work. */
        NO_COPY_ON_THE_WORK("skipped", "no_copy_on_the_work"),
        /** The legacy composition could not compose from the retained inputs either. */
        INPUTS_UNUSABLE("skipped", "inputs_unusable"),
        /** No public result is Not met, so the work place has nothing to say. */
        NOTHING_PUBLIC("skipped", "nothing_public"),
        /** Another seal of the review wrote it. */
        ALREADY_WRITTEN("skipped", "already_written"),
        /** A retained input did not decode, or a reference it needs is missing. */
        UNREADABLE("failed", "unreadable");

        final String outcome;
        final String reason;

        Decision(String outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }
    }

    private record Built(@Nullable WorkPlaceHandoffRow handoff, Decision decision) {
        static Built skipped(Decision decision) {
            return new Built(null, decision);
        }
    }

    private final ReviewJobRepository jobs;
    private final ReviewJobAssignments assignments;
    private final ItemDescriptorRegistry items;
    private final ReviewCompositionContinuation continuation;
    private final ReviewCompositionBasisRepository bases;
    private final AdmittedResultQuery admitted;
    private final AdmittedResultProjectionService projection;
    private final PracticeStagingStore staging;
    private final WorkPlaceHandoffRepository handoffs;
    private final ObjectMapper mapper;
    private final Map<Decision, Counter> decisions = new EnumMap<>(Decision.class);

    WorkPlaceHandoffEnrolment(
            ReviewJobRepository jobs,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            ReviewCompositionContinuation continuation,
            ReviewCompositionBasisRepository bases,
            AdmittedResultQuery admitted,
            AdmittedResultProjectionService projection,
            PracticeStagingStore staging,
            WorkPlaceHandoffRepository handoffs,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.jobs = jobs;
        this.assignments = assignments;
        this.items = items;
        this.continuation = continuation;
        this.bases = bases;
        this.admitted = admitted;
        this.projection = projection;
        this.staging = staging;
        this.handoffs = handoffs;
        this.mapper = mapper;
        // Every series exists from the start, so a rule on the failed ones reads zero before the first failure.
        for (var decision : Decision.values())
            decisions.put(
                    decision,
                    Counter.builder(AgentMetrics.AGENT_JOB_WORK_PLACE_HANDOFF)
                            .description("Sealed reviews by what their seal did with the work-place handoff")
                            .tag("outcome", decision.outcome)
                            .tag("reason", decision.reason)
                            .register(meters));
    }

    /**
     * Writes the handoff of the review this transaction sealed, once, when its work place has something to read. A
     * database failure rolls the seal back, and so does a failure after which a transactional collaborator marked the
     * seal for rollback. Any other failure is recorded and leaves the seal to commit.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ReviewSealed sealed) {
        Decision decision;
        if (handoffs.existsByWorkspaceIdAndReviewId(sealed.workspaceId(), sealed.reviewId()))
            decision = Decision.ALREADY_WRITTEN;
        else {
            Built built;
            try {
                built = build(sealed);
            } catch (DataAccessException | TransactionException database) {
                throw database;
            } catch (RuntimeException failure) {
                // A transactional collaborator that throws marks the seal for rollback: the seal cannot commit then.
                if (TransactionInterceptor.currentTransactionStatus().isRollbackOnly()) throw failure;
                log.warn(
                        "Work-place handoff could not be built: jobId={}, errorType={}",
                        sealed.jobId(),
                        failure.getClass().getName());
                built = Built.skipped(Decision.UNREADABLE);
            }
            var handoff = built.handoff();
            decision =
                    handoff == null || handoffs.insertOnce(handoff) == 1 ? built.decision() : Decision.ALREADY_WRITTEN;
        }
        TransactionCallbacks.afterCommit(Objects.requireNonNull(decisions.get(decision))::increment);
    }

    /** Reads the sealed rows, then decodes and scopes them in memory. It writes nothing. */
    private Built build(ReviewSealed sealed) {
        var job = jobs.findByIdAndWorkspaceId(sealed.jobId(), sealed.workspaceId())
                .orElseThrow();
        var review = assignments.review(job);
        if (review.kind() != ReviewKind.ITEM
                || review.origin() == ReviewOrigin.TRIAL
                || review.origin() == ReviewOrigin.BACKFILL
                || !PULL_REQUEST.equals(review.item().kind())) return Built.skipped(Decision.INELIGIBLE);
        var capabilities = items.descriptorFor(review.item().kind())
                .map(ItemDescriptor::copyCapabilities)
                .orElse(Set.of());
        var request = FeedbackCompositionInputs.request(
                        review.kind(), review.origin(), Set.of(FeedbackDestination.ON_WORK), capabilities, mapper)
                .map(mapper::readTree)
                .orElse(null);
        if (request == null) return Built.skipped(Decision.NO_COPY_ON_THE_WORK);
        Map<String, byte[]> inputs;
        try {
            inputs = continuation.retainedInputs(job);
        } catch (ReviewCompositionContinuation.InputsUnavailableException unusable) {
            // The legacy composition cannot compose from these inputs either, so the work place gets nothing.
            return Built.skipped(Decision.INPUTS_UNUSABLE);
        }
        var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElseThrow();
        var results = new ArrayList<JsonNode>();
        projection
                .results(
                        job,
                        admitted.forAttempt(
                                job.getWorkspaceId(),
                                job.getReviewId(),
                                job.getId(),
                                ResultAdmissionService.citationAttempt(job)))
                .forEach(result -> results.add(mapper.valueToTree(result)));
        var forms = new ArrayList<String>();
        request.path("onWorkCopyForms").forEach(form -> forms.add(form.asString()));
        var captured = new LinkedHashSet<String>();
        if (job.getCapture() != null)
            job.getCapture()
                    .path("manifest")
                    .path("sources")
                    .forEach(source -> captured.add(source.path("kind").asString()));
        var slugs = new LinkedHashMap<List<Long>, String>();
        assignments
                .load(job)
                .forEach(assignment -> slugs.put(
                        List.of(assignment.id().practiceId(), assignment.id().itemId()), assignment.practiceSlug()));
        var copies = new HashMap<String, List<WorkPlaceScope.Copy>>();
        var expiries = new HashMap<String, Instant>();
        for (var practice : staging.staged(job.getWorkspaceId(), job.getReviewId(), job.getId())) {
            String slug = slugs.get(List.of(
                    practice.assignment().practiceId(), practice.assignment().itemId()));
            if (practice.candidate() == null || slug == null) continue;
            copies.computeIfAbsent(slug, ignored -> new ArrayList<>())
                    .add(new WorkPlaceScope.Copy(
                            UUID.fromString(practice.evidence().path("copyId").asString()), practice.attempt()));
            expiries.merge(
                    slug,
                    Instant.parse(practice.evidence().path("expiresAt").asString()),
                    (kept, staged) -> staged.isBefore(kept) ? staged : kept);
        }
        Instant now = Instant.now();
        var scoped = WorkPlaceScope.of(new WorkPlaceScope.Sealed(
                inputs,
                results,
                captured,
                List.copyOf(new LinkedHashSet<>(slugs.values())),
                forms,
                new WorkPlaceScope.Copy(basis.getCopyId(), basis.getAdmissionAttempt()),
                copies,
                expiries,
                now,
                review.evidenceExpiresAt()));
        if (scoped.isEmpty()) return Built.skipped(Decision.NOTHING_PUBLIC);
        var copy = scoped.orElseThrow();
        ItemVersion version = review.version();
        return new Built(
                WorkPlaceHandoffRow.builder()
                        .id(id(job.getWorkspaceId(), job.getReviewId()))
                        .workspaceId(job.getWorkspaceId())
                        .reviewId(job.getReviewId())
                        .jobId(job.getId())
                        .itemId(review.item().itemId())
                        .itemVersion(version == null ? null : version.value())
                        .producer(WorkPlaceHandoffRow.Producer.LEGACY)
                        .payload(copy.payload())
                        .payloadDigest(copy.digest())
                        .expiresAt(copy.expiresAt())
                        .createdAt(now)
                        .build(),
                Decision.WRITTEN);
    }

    /** The review names its handoff, so every seal of it writes the same row. */
    static UUID id(long workspaceId, UUID reviewId) {
        return UUID.nameUUIDFromBytes(
                ("work-place-handoff:" + workspaceId + ":" + reviewId).getBytes(StandardCharsets.UTF_8));
    }
}
