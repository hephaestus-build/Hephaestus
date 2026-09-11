package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared raw-JDBC seed helpers for the Slack conversation SPI integration tests
 * ({@code SlackConversationProjectorIntegrationTest}, {@code ConversationThreadDetectionIntegrationTest}).
 *
 * <p>Two things are single-sourced here:
 *
 * <ul>
 *   <li>{@link #ensureUnmappedSlackThreadColumns()} — idempotent {@code IF NOT EXISTS} DDL for the
 *       {@code participant_member_ids bigint[]} + GIN index and {@code last_reviewed_ts VARCHAR(32)} column,
 *       so the two SPI ITs cannot drift from each other on this DDL. That the shape actually matches the
 *       production Liquibase migration is proven independently by
 *       {@code SlackConversationSchemaContractIntegrationTest} against the real schema.</li>
 *   <li>{@link #seedChannel}/{@link #seedThread}/{@link #seedMessage} — the raw {@code INSERT}s the projector and
 *       detection scans read over. Superset signatures so both callers share one SQL string per table.</li>
 * </ul>
 *
 * <p>The seed helpers are also reused by the real-Liquibase {@code SlackConversationSchemaContractIntegrationTest}
 * (where {@link #ensureUnmappedSlackThreadColumns()} is deliberately NOT called — those columns already exist as the
 * real migrated types).
 */
public final class SlackConversationTestSupport {

    private final JdbcTemplate jdbc;

    public SlackConversationTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotently add the raw-JDBC-only {@code slack_thread} columns to the entity-derived test schema, mirroring
     * the production Liquibase migration. No-op on the real Liquibase schema (the columns already exist).
     */
    public void ensureUnmappedSlackThreadColumns() {
        jdbc.execute(
                "ALTER TABLE slack_thread ADD COLUMN IF NOT EXISTS participant_member_ids BIGINT[] NOT NULL DEFAULT '{}'");
        jdbc.execute("ALTER TABLE slack_thread ADD COLUMN IF NOT EXISTS last_reviewed_ts VARCHAR(32)");
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_slack_thread_participants ON slack_thread USING GIN (participant_member_ids)");
    }

    /** Seed one monitored channel with an explicit consent state. */
    public void seedChannel(long workspaceId, String channelId, String consentState) {
        jdbc.update(
                "INSERT INTO slack_monitored_channel (workspace_id, slack_team_id, slack_channel_id, channel_name, consent_state, created_at) "
                        + "VALUES (?, 'T1', ?, 'engineering', ?, now())",
                workspaceId,
                channelId,
                consentState);
    }

    /**
     * Seed a thread aggregate with an explicit participant member-id set (text array literal → {@code bigint[]}).
     *
     * @param participantArrayLiteral e.g. {@code "{100,101}"}
     */
    public void seedThread(
            long workspaceId,
            String channelId,
            @Nullable String threadTs,
            String lastTs,
            int messageCount,
            String participantArrayLiteral) {
        jdbc.update(
                "INSERT INTO slack_thread (workspace_id, slack_channel_id, slack_thread_ts, first_ts, last_ts, message_count, participant_member_ids, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS bigint[]), now())",
                workspaceId,
                channelId,
                threadTs,
                threadTs,
                lastTs,
                messageCount,
                participantArrayLiteral);
    }

    /** Seed one non-tombstoned message (author {@code U1}). */
    public void seedMessage(long workspaceId, String channelId, String ts, @Nullable String threadTs, String text) {
        jdbc.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, slack_thread_ts, author_slack_user_id, text, ingested_at) "
                        + "VALUES (?, 'T1', ?, ?, ?, 'U1', ?, now())",
                workspaceId,
                channelId,
                ts,
                threadTs,
                text);
    }

    private static final ObjectMapper OM = new ObjectMapper();

    /** Result of {@link #seedBoundConversation}: the ids of the two rows it inserted, for later erasure assertions. */
    public record BoundConversation(UUID observationId, UUID feedbackId) {}

    /**
     * Saves a CONVERSATION-channel {@link Practice} + {@link AgentJob} owned by the given workspace. Shared by the
     * consent-lifecycle E2E, opt-out erasure, and channel-admin controller integration tests, which each seed exactly
     * this fixture pair before deriving observation/feedback rows from it.
     */
    public static AgentJob newConversationReviewJob(AgentJobRepository agentJobRepository, Workspace workspace) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.CONVERSATION_REVIEW);
        job.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        return agentJobRepository.save(job);
    }

    /** Saves a minimal {@link Practice} owned by the given workspace, slugged so repeated calls do not collide. */
    public static Practice newPractice(PracticeRepository practiceRepository, Workspace workspace, String slugPrefix) {
        Practice practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug(slugPrefix + "-" + UUID.randomUUID());
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        return practiceRepository.save(practice);
    }

    /**
     * Inserts a derived chat.conversation_thread {@link de.tum.cit.aet.hephaestus.practices.observation.Observation} +
     * {@link Feedback} pair about {@code aboutUserId}, anchored to {@code threadId} — the shape the Slack consent
     * erasure paths (person opt-out, channel revoke) must sweep.
     */
    public static BoundConversation seedBoundConversation(
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            long workspaceId,
            UUID jobId,
            long practiceId,
            long threadId,
            long aboutUserId) {
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "occ-" + observationId,
                jobId,
                workspaceId,
                practiceId,
                null,
                ArtifactKinds.CONVERSATION_THREAD.value(),
                threadId,
                aboutUserId,
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(jobId)
                .workspaceId(workspaceId)
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(aboutUserId)
                .aboutUserId(aboutUserId)
                .channel(FeedbackChannel.IN_CHAT)
                .position((int) ((threadId * 10 + aboutUserId) % 1000))
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
        return new BoundConversation(observationId, feedback.getId());
    }
}
