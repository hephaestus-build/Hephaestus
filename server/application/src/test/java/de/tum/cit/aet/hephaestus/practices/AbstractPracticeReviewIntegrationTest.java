package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
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
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
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

    /** The developer's own in-app feedback page. */
    protected static final String IN_APP = "/workspaces/{slug}/practices/feedback/in-app";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected WebTestClient webTestClient;

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
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.2.0"))));
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
        return observe(
                practice,
                job,
                ArtifactKinds.PULL_REQUEST.value(),
                artifactId,
                about,
                null,
                presence,
                assessment,
                severity,
                observedAt,
                evidenceJson,
                null);
    }

    /**
     * {@link #observe} on the given artifact kind, with the summary a test reads back out of the payload and the
     * recurrence key that dates a repeat. A null title stands for "any summary", so a test that does not read one
     * does not have to invent it.
     */
    protected UUID observe(
            Practice practice,
            AgentJob job,
            String artifactKind,
            long artifactId,
            User about,
            @Nullable String title,
            String presence,
            @Nullable String assessment,
            @Nullable String severity,
            Instant observedAt,
            String evidenceJson,
            @Nullable String recurrenceKey) {
        UUID id = UUID.randomUUID();
        String summary = title == null ? "Observation " + id : title;
        observationRepository.insertIfAbsent(
                id,
                "occ-" + id,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                about.getId(),
                summary,
                assessment == null ? presence : "ASSESSED",
                assessment == null ? null : presence,
                assessment,
                severity,
                evidenceJson,
                "Reasoning for " + summary,
                recurrenceKey,
                observedAt,
                "LIVE");
        return id;
    }

    /**
     * One piece of in-app feedback {@code job} prepared for {@code recipient}; a DELIVERED row is stamped
     * delivered at {@code createdAt}.
     */
    protected Feedback persistInAppFeedback(
            AgentJob job, User recipient, int position, FeedbackDeliveryState state, String body, Instant createdAt) {
        return persistFeedback(job, recipient, FeedbackChannel.IN_APP, position, state, body, createdAt);
    }

    /** {@link #persistInAppFeedback} on the given channel, for a test about where the feedback was meant to appear. */
    protected Feedback persistFeedback(
            AgentJob job,
            User recipient,
            FeedbackChannel channel,
            int position,
            FeedbackDeliveryState state,
            String body,
            Instant createdAt) {
        return feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(channel)
                .position(position)
                .deliveryState(state)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? createdAt : null)
                .build());
    }

    /** Binds the observation the feedback was written from. */
    protected void bind(Feedback feedback, UUID observationId) {
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, "PRIMARY", 0);
    }

    /**
     * The developer's response as the response endpoint records it: a snapshot of what now stands on the card.
     * A null resolution is the response they deleted, which the readers treat as no response at all.
     */
    protected Reaction respond(
            Feedback feedback, User developer, @Nullable FeedbackResolution resolution, Instant respondedAt) {
        return reactionRepository.save(Reaction.builder()
                .feedback(feedback)
                .reactorUserId(developer.getId())
                .resolution(resolution)
                .createdAt(respondedAt)
                .build());
    }

    /** The developer's response that marks the card addressed. */
    protected Reaction markAddressed(Feedback feedback, User developer, Instant respondedAt) {
        return respond(feedback, developer, FeedbackResolution.ADDRESSED, respondedAt);
    }

    /** A review of one of the developer's pull requests on which the practice raised nothing. */
    protected void cleanReview(Practice practice, User developer, int number, Instant reviewedAt) {
        AgentJob run = persistPullRequestReview(practice.getWorkspace(), number, reviewedAt);
        observe(practice, run, number, developer, "PRESENT", "GOOD", null, reviewedAt);
    }

    /** The in-app feedback page as the signed-in developer reads it. */
    protected WebTestClient.BodyContentSpec readInAppPage(Workspace workspace) {
        return webTestClient
                .get()
                .uri(IN_APP, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    /** A workspace page read by an instance administrator in a read-only user view of {@code viewed}. */
    protected WebTestClient.BodyContentSpec readAsUserView(String uri, Workspace workspace, User viewed) {
        Account administrator = persistInstanceAdmin("Practice inspector");
        return webTestClient
                .get()
                .uri(uri, workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + administrator.getId()))
                .header(UserViewContextHolder.WORKSPACE_HEADER, workspace.getWorkspaceSlug())
                .header(UserViewContextHolder.USER_HEADER, String.valueOf(viewed.getId()))
                .header(UserViewContextHolder.REASON_HEADER, "Check the practice profile")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }
}
