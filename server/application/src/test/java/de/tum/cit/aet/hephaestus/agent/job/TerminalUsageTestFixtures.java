package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

public final class TerminalUsageTestFixtures {
    private TerminalUsageTestFixtures() {}

    /** Projects the job row's proxy counters as one attempt, the way a stopped attempt without a report is billed. */
    public static void appendProxyUsage(
            ReviewJobRepository jobs, LlmUsageRecorder recorder, ReviewJob job, ObjectMapper mapper) {
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        project(
                TerminalUsage.fromProxy(jobs.findLlmUsageById(job.getId()).orElse(null)),
                recorder,
                jobs,
                job.getWorkspaceId(),
                job,
                snapshot,
                Objects.requireNonNull(snapshot.model().priceSnapshot()));
    }

    /** Projects one selection with the charges of {@code snapshot}, as a terminal path of that attempt would. */
    static boolean project(
            TerminalUsage usage,
            LlmUsageRecorder recorder,
            ReviewJobRepository jobs,
            long workspaceId,
            ReviewJob job,
            @Nullable ConfigSnapshot snapshot,
            LlmPriceSnapshot price) {
        return usage.projectTo(
                recorder,
                new TerminalUsage.Source(workspaceId, job.getReviewId(), job.getId(), job.getAttempt(), Instant.now()),
                FrozenCharge.admitted(snapshot, price),
                jobs.sumPracticeScriptUsageByKind(workspaceId, job.getId(), job.getAttempt()));
    }

    /**
     * A settlement service over a mocked job repository, for unit tests of the terminal paths. The ledger samples
     * reach {@code recorder}'s {@code project}; the usage lock answers with the job each test stubbed.
     */
    public static ReviewAttemptUsageService attemptUsageOverMocks(ReviewJobRepository jobs, LlmUsageRecorder recorder) {
        lockedAsStubbed(jobs);
        return new ReviewAttemptUsageService(
                jobs, Mockito.mock(ReviewAttemptUsageRepository.class), recorder, Mockito.mock(AgentRuns.class));
    }

    /**
     * Lets a mocked repository answer the usage lock with the job the test stubbed for the same id: the row the
     * terminal path holds, on that job's attempt and status.
     */
    public static void lockedAsStubbed(ReviewJobRepository jobs) {
        Mockito.lenient()
                .doAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    long workspaceId = invocation.getArgument(1);
                    Optional<ReviewJob> job = jobs.findByIdAndWorkspaceId(id, workspaceId);
                    if (job.isEmpty()) job = jobs.lockById(id);
                    if (job.isEmpty()) job = jobs.findById(id);
                    return job.map(TerminalUsageTestFixtures::heldAs);
                })
                .when(jobs)
                .lockUsageOwner(ArgumentMatchers.any(), ArgumentMatchers.anyLong());
    }

    private static ReviewJobRepository.UsageOwner heldAs(ReviewJob job) {
        String status = job.getStatus().name();
        int attempt = job.getAttempt();
        UUID reviewId = job.getReviewId();
        return new ReviewJobRepository.UsageOwner() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public int getAttempt() {
                return attempt;
            }

            @Override
            public UUID getReviewId() {
                return reviewId;
            }
        };
    }
}
