package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMethodVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewJobService implements ReviewQueue {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobService.class);
    private final ReviewJobRepository jobs;
    private final ReviewWriteGuard writeGuard;
    private final ReviewJobAssignments assignments;
    private final ReviewAssignmentStore assignmentStore;
    private final ReviewMemberAiPolicy policy;
    private final LlmModelResolver models;
    private final ObjectMapper mapper;
    private final LlmAdmissionService admission;
    private final ReviewMethodVerifier methods;
    private final AgentRuns runs;

    @Override
    @Transactional
    public UUID enqueue(ReviewSnapshot review, long itemId, List<ReviewAssignmentId> selected) {
        if (selected.isEmpty()) throw new IllegalArgumentException("A review job needs assigned practices");
        writeGuard.lock(review.workspaceId(), review.id());
        var active = jobs.findByReviewIdAndWorkspaceIdAndStatusIn(
                review.id(), review.workspaceId(), Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING));
        if (active.isPresent()) {
            if (!active.get().getInput().equals(new ReviewJobInput(itemId, selected))) {
                throw new ReviewJobStateConflictException("The review already has another active invocation");
            }
            return active.get().getId();
        }
        ReviewJob job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setReviewId(review.id());
        job.setWorkspaceId(review.workspaceId());
        job.setInput(new ReviewJobInput(itemId, selected));
        job.setRunId(runs.enqueue(
                        review.workspaceId(),
                        AgentRunKind.REVIEW,
                        ReviewRunKind.band(review.origin()),
                        ReviewRunKind.subject(review.id()),
                        null)
                .id());
        var pinned = assignments.load(job);
        ReviewMethod method = pinned.getFirst().practice().method();
        if (pinned.stream()
                .anyMatch(assignment -> !assignment.practice().method().equals(method))) {
            throw new IllegalArgumentException("One invocation must use one frozen review method");
        }
        var binding = policy.binding(job)
                .orElseThrow(() -> new ReviewJobStateConflictException("No permitted model serves these assignments"));
        ConfigSnapshot snapshot = ConfigSnapshot.from(binding, models)
                .withPriceSnapshot(admission.admit(binding).price());
        methods.require(method, "review", binding);
        EnumMap<ModelKind, FrozenModel> direct = new EnumMap<>(ModelKind.class);
        if (slot(method, "chat").model() != null) {
            methods.require(method, "chat", binding);
            direct.put(ModelKind.CHAT, snapshot.model());
        }
        for (AgentPurpose purpose : AgentPurpose.practiceScript()) {
            BoundModelSlot slot = slot(method, purpose.kind().name().toLowerCase(Locale.ROOT));
            if (slot.model() == null) continue;
            var scriptBinding = policy.practiceScriptBinding(job, purpose)
                    .orElseThrow(
                            () -> new ReviewJobStateConflictException("A frozen practice script model is unavailable"));
            FrozenModel model = FrozenModel.from(scriptBinding, models)
                    .withPriceSnapshot(admission.admit(scriptBinding).price());
            methods.require(method, slot.slot(), scriptBinding);
            direct.put(purpose.kind(), model);
        }
        job.setConfigSnapshot(mapper.valueToTree(snapshot.withPracticeScript(direct)));
        jobs.saveAndFlush(job);
        assignmentStore.bind(review.workspaceId(), review.id(), job.getId(), selected);
        UUID jobId = job.getId();
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "agent.job.queued")
                .addKeyValue(StructuredLogKeys.JOB_ID, jobId)
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, review.workspaceId())
                .addKeyValue("review.id", review.id())
                .addKeyValue("job.phase", "queue")
                .addKeyValue("job.outcome", "queued")
                .addKeyValue("duration.ms", 0)
                .log("Agent job lifecycle transition"));
        return jobId;
    }

    private static BoundModelSlot slot(ReviewMethod method, String name) {
        return method.models().stream()
                .filter(slot -> slot.slot().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Frozen review method is missing slot: " + name));
    }

    @Transactional(readOnly = true)
    public ReviewJob getJob(long workspaceId, UUID jobId) {
        return jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ReviewJob", jobId.toString()));
    }
}
