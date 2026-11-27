package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.composer.WorkPlaceScope;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkPlaceHandoffRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkPlaceHandoffRow;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class WorkPlaceHandoffEnrolment {
    /** The one item kind with a work place in 1.0: a GitHub pull request or a GitLab merge request. */
    static final ItemKind PULL_REQUEST = new ItemKind("scm.pull_request");

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

    /**
     * Writes the handoff of the review this transaction sealed, once, when its work place has something to read. A
     * plain listener: it runs in the seal's transaction, and a failure rolls the seal back.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ReviewSealed sealed) {
        if (handoffs.existsByWorkspaceIdAndReviewId(sealed.workspaceId(), sealed.reviewId())) return;
        var job = jobs.findByIdAndWorkspaceId(sealed.jobId(), sealed.workspaceId())
                .orElseThrow();
        var review = assignments.review(job);
        if (review.kind() != ReviewKind.ITEM
                || review.origin() == ReviewOrigin.TRIAL
                || review.origin() == ReviewOrigin.BACKFILL
                || !PULL_REQUEST.equals(review.item().kind())) return;
        var capabilities = items.descriptorFor(review.item().kind())
                .map(ItemDescriptor::copyCapabilities)
                .orElse(Set.of());
        var request = FeedbackCompositionInputs.request(
                        review.kind(), review.origin(), Set.of(FeedbackDestination.ON_WORK), capabilities, mapper)
                .map(mapper::readTree)
                .orElse(null);
        if (request == null) return;
        Map<String, byte[]> inputs;
        try {
            inputs = continuation.retainedInputs(job);
        } catch (ReviewCompositionContinuation.InputsUnavailableException unusable) {
            // The legacy composition cannot compose from these inputs either, so the work place gets nothing.
            return;
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
        if (scoped.isEmpty()) return;
        var copy = scoped.orElseThrow();
        ItemVersion version = review.version();
        handoffs.save(WorkPlaceHandoffRow.builder()
                .id(UUID.randomUUID())
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
                .build());
    }
}
