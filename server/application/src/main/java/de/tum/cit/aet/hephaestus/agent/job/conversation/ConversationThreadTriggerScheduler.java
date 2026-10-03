package de.tum.cit.aet.hephaestus.agent.job.conversation;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationCandidateSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.agent.job.ConversationReviewSubmitter;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds settled Slack conversation threads that are ready for a communication-practice review and enqueues
 * one {@link AgentJobType#CONVERSATION_REVIEW} job per human participant.
 *
 * <p>Three gates, all deterministic:
 * <ul>
 *   <li><b>Quiescence</b> — no new message for {@value #QUIESCENCE_MINUTES} minutes (the thread has settled).</li>
 *   <li><b>Depth</b> — at least {@value #MIN_HUMAN_TURNS} non-tombstoned turns (a real exchange, not a one-liner).</li>
 *   <li><b>Growth</b> — at least {@value #MIN_GROWTH} new non-tombstoned turns since
 *       {@code slack_thread.last_reviewed_ts} (the watermark), so a re-sweep with no fresh human turn past
 *       the watermark enqueues nothing.</li>
 * </ul>
 *
 * <p>Cooldown is keyed on the thread + subject alone, NOT on {@code threadId + lastTs}, so a late reply does
 * not immediately re-fire — only genuine growth past the watermark does. The gates stay in front of the
 * ledger rather than being replaced by the occurrence's identity: identity moves on a single new turn but
 * {@link #MIN_GROWTH} requires two, so dedup alone would quietly raise how often conversations get reviewed.
 *
 * <p>A thread that passes the gates is recorded as one {@code chat.conversation_thread.settled} occurrence,
 * so a thread passed over leaves a reason behind instead of silent nothing.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic(
        "Cross-workspace conversation-thread sweep on a schedule; the candidate scan / counts / watermark advance "
                + "delegate to the Slack-implemented ConversationCandidateSource SPI (workspace-pinned there) and the "
                + "enqueue delegates to AgentJobService#submit, which scopes its own writes (same inherently cross-workspace "
                + "pattern as SlackRetentionSweeper)")
public class ConversationThreadTriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConversationThreadTriggerScheduler.class);

    static final int QUIESCENCE_MINUTES = 10;

    static final int MIN_HUMAN_TURNS = 4;

    static final int MIN_GROWTH = 2;

    private final ConversationCandidateSource candidateSource;
    private final ConversationReviewSubmitter submitter;
    private final SignalRecorder signalRecorder;
    private final TransactionTemplate transactionTemplate;

    /**
     * When {@code false} the sweep no-ops, keeping conversation review dormant in lockstep with
     * {@link de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService}'s channel-ingest gate.
     */
    private final boolean conversationIngestEnabled;

    public ConversationThreadTriggerScheduler(
            ConversationCandidateSource candidateSource,
            ConversationReviewSubmitter submitter,
            SignalRecorder signalRecorder,
            TransactionTemplate transactionTemplate,
            @Value("${hephaestus.integration.slack.conversation-ingest.enabled:true}")
                    boolean conversationIngestEnabled) {
        this.candidateSource = candidateSource;
        this.submitter = submitter;
        this.signalRecorder = signalRecorder;
        this.transactionTemplate = transactionTemplate;
        this.conversationIngestEnabled = conversationIngestEnabled;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "conversation-thread-review", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /**
     * Runs the sweep immediately across every workspace.
     *
     * @return the number of conversation-review jobs enqueued this run
     */
    public long sweepNow() {
        if (!conversationIngestEnabled) {
            return 0;
        }
        List<ConversationThreadCandidate> candidates = candidateSource.settledCandidates(MIN_HUMAN_TURNS);
        Instant now = Instant.now();
        long enqueued = 0;
        for (ConversationThreadCandidate c : candidates) {
            long totalTurns = candidateSource.liveTurnCount(c.workspaceId(), c.channelId(), c.threadTs());
            long growth = candidateSource.liveTurnCountSince(
                    c.workspaceId(), c.channelId(), c.threadTs(), c.lastReviewedTs());
            if (!passesGates(now, c.lastTs(), totalTurns, growth, QUIESCENCE_MINUTES, MIN_HUMAN_TURNS, MIN_GROWTH)) {
                continue;
            }
            // The occurrence goes into the ledger BEFORE anything is submitted, and the ledger's own
            // uniqueness decides whether this sweep is the one that acts on it.
            SignalKey key =
                    ChatSignals.threadSettledKey(c.workspaceId(), c.threadId(), c.threadTs(), c.lastTs(), totalTurns);
            boolean ours = transactionTemplate.execute(status -> signalRecorder.record(key, now, DiscoveredVia.SYNC));
            if (!Boolean.TRUE.equals(ours)) {
                // Another sweep already decided this exact occurrence — the gates run on counts read a
                // moment ago, so two overlapping sweeps genuinely can agree the same thread is ready.
                continue;
            }
            long started = submitter.submitAndSettle(c, key);
            enqueued += started;
            // Advance the watermark ONLY after at least one job was enqueued, so a workspace with no enabled
            // agent config keeps re-appearing as a candidate and catches up once one is configured.
            if (started > 0) {
                candidateSource.markReviewed(c.workspaceId(), c.threadId(), c.lastTs());
            }
        }
        if (enqueued > 0) {
            log.info(
                    "conversation.review: enqueued {} review job(s) across {} candidate thread(s)",
                    enqueued,
                    candidates.size());
        }
        return enqueued;
    }

    /** Pure gate predicate, unit-tested directly. */
    static boolean passesGates(
            Instant now,
            @Nullable String lastTs,
            long totalTurns,
            long growthSinceWatermark,
            int quiescenceMinutes,
            int minHumanTurns,
            int minGrowth) {
        if (totalTurns < minHumanTurns) {
            return false;
        }
        if (growthSinceWatermark < minGrowth) {
            return false;
        }
        Long lastEpoch = slackTsEpochSeconds(lastTs);
        if (lastEpoch == null) {
            return false;
        }
        long ageSeconds = now.getEpochSecond() - lastEpoch;
        return ageSeconds >= (long) quiescenceMinutes * 60L;
    }

    /**
     * Parse the integer-second part of a Slack {@code ts} ({@code "1700000000.123456"}). Returns {@code null}
     * for a null / unparseable value (which the gate treats as not-yet-ready rather than throwing).
     */
    static @Nullable Long slackTsEpochSeconds(@Nullable String slackTs) {
        if (slackTs == null || slackTs.isBlank()) {
            return null;
        }
        int dot = slackTs.indexOf('.');
        String secs = dot >= 0 ? slackTs.substring(0, dot) : slackTs;
        try {
            return Long.parseLong(secs.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
