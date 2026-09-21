package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds one practice review's history the way the production writers leave it: a practice with a current
 * revision, a pull request review run with the metadata the target lookup reads, the observations the run
 * recorded, the in-app feedback composed from them and the developer's responses. Every helper returns the
 * row it wrote, so a test asserts on its own rows and never on a count.
 */
public abstract class AbstractPracticeReviewIntegrationTest extends AbstractWorkspaceIntegrationTest {

    protected static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\","
                    + "\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\","
                    + "\"quoteRedacted\":false}]}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected PracticeRepository practiceRepository;

    @Autowired
    protected PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    protected ObservationRepository observationRepository;

    @Autowired
    protected AgentJobRepository agentJobRepository;

    @Autowired
    protected FeedbackRepository feedbackRepository;

    @Autowired
    protected FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    protected ReactionRepository reactionRepository;

    /** A pull-request practice with its first revision current, as the catalog installs one. */
    protected Practice persistPractice(
            Workspace workspace,
            @Nullable PracticeGroup group,
            String slug,
            String name,
            @Nullable String sourceCuratedSlug) {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Criteria for " + slug);
        practice.setGroup(group);
        practice.setSourceCuratedSlug(sourceCuratedSlug);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.saveAndFlush(practice);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 1)));
        return practiceRepository.saveAndFlush(practice);
    }

    /** A completed review of pull request {@code #number} of {@code acme/api}, named the way the target lookup reads it. */
    protected AgentJob persistPullRequestReview(Workspace workspace, int number, @Nullable Instant completedAt) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setIntegrationKind(IntegrationKind.GITHUB);
        job.setMetadata(OBJECT_MAPPER.valueToTree(Map.of(
                "pull_request_id",
                (long) number,
                "pr_number",
                number,
                "title",
                "Pull request " + number,
                "repository_full_name",
                "acme/api",
                "pr_url",
                "https://github.com/acme/api/pull/" + number)));
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        if (completedAt != null) {
            job.setCompletedAt(completedAt);
        }
        return agentJobRepository.save(job);
    }

    /** One live observation of {@code practice} about {@code about}, recorded by {@code job} on a pull request. */
    protected UUID observe(
            Practice practice,
            AgentJob job,
            long artifactId,
            User about,
            String presence,
            @Nullable String assessment,
            @Nullable String severity,
            Instant observedAt) {
        return observe(
                practice, job, artifactId, about, presence, assessment, severity, observedAt, DIFF_EVIDENCE_JSON);
    }

    /** {@link #observe} citing the given evidence, for a test about what the evidence may be used for. */
    protected UUID observe(
            Practice practice,
            AgentJob job,
            long artifactId,
            User about,
            String presence,
            @Nullable String assessment,
            @Nullable String severity,
            Instant observedAt,
            String evidenceJson) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occ-" + id,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                ArtifactKinds.PULL_REQUEST.value(),
                artifactId,
                about.getId(),
                "Observation " + id,
                presence,
                assessment,
                severity,
                evidenceJson,
                "Reasoning",
                null,
                observedAt,
                "LIVE");
        return id;
    }

    /** A readable in-app card {@code job} composed for {@code recipient}; a delivered one was read when it was prepared. */
    protected Feedback persistInAppFeedback(
            AgentJob job, User recipient, int position, FeedbackDeliveryState state, String body, Instant createdAt) {
        return feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_APP)
                .position(position)
                .deliveryState(state)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? createdAt : null)
                .build());
    }

    /** Binds the observation the card was written from. */
    protected void bind(Feedback feedback, UUID observationId) {
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, "PRIMARY", 0);
    }

    /** The developer's response that marks the card addressed, as the response endpoint records it. */
    protected Reaction markAddressed(Feedback feedback, User developer, Instant respondedAt) {
        return reactionRepository.save(Reaction.builder()
                .feedback(feedback)
                .reactorUserId(developer.getId())
                .resolution(FeedbackResolution.ADDRESSED)
                .createdAt(respondedAt)
                .build());
    }
}
