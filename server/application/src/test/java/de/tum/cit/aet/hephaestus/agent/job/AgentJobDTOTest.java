package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentJobDTOTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FULL_URL = "https://gateway.example.com/v1/openai?tenant=secret-project";
    private static final String REDACTED_URL = "https://gateway.example.com";

    static Stream<Arguments> scopes() {
        return Stream.of(
                Arguments.of(FundingSource.INSTANCE, REDACTED_URL, "an instance connection is redacted"),
                Arguments.of(null, REDACTED_URL, "a legacy null-scope config is redacted too"),
                Arguments.of(FundingSource.WORKSPACE, FULL_URL, "a BYO connection is the workspace's own config"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("scopes")
    void redactsBaseUrlUnlessTheWorkspaceOwnsTheConnection(
            @Nullable FundingSource scope, String expectedBaseUrl, String why) {
        AgentJobDTO dto = AgentJobDTO.from(jobWithSnapshot(snapshotWithScope(scope)));

        ObjectNode snapshot = (ObjectNode) dto.configSnapshot();
        assertThat(snapshot.path("baseUrl").asString()).as(why).isEqualTo(expectedBaseUrl);
    }

    /**
     * A budget-held job is QUEUED like any other, so without these two fields the console shows it as
     * "waiting for a worker" and an admin has no way to tell that raising the cap is what releases it.
     */
    @Test
    void carriesTheHoldReasonAndReleaseTimeOfABudgetHeldJob() {
        AgentJob job = jobWithSnapshot(snapshotWithScope(FundingSource.INSTANCE));
        Instant releaseAt = job.getCreatedAt().plus(Duration.ofHours(6));
        job.setStatus(AgentJobStatus.QUEUED);
        job.setAvailableAt(releaseAt);
        job.setHoldReason(AgentJob.HOLD_REASON_BUDGET);

        AgentJobDTO dto = AgentJobDTO.from(job);

        assertThat(dto.holdReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        assertThat(dto.availableAt()).isEqualTo(releaseAt);
    }

    @Test
    void reportsNoHoldForAnOrdinaryQueuedJob() {
        AgentJob job = jobWithSnapshot(snapshotWithScope(FundingSource.INSTANCE));
        job.setStatus(AgentJobStatus.QUEUED);

        AgentJobDTO dto = AgentJobDTO.from(job);

        assertThat(dto.holdReason()).isNull();
        assertThat(dto.availableAt())
                .as("defaulted to submit time by prePersist, never null")
                .isNotNull();
    }

    private static AgentJob jobWithSnapshot(ConfigSnapshot snapshot) {
        AgentJob job = new AgentJob();
        job.prePersist();
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(snapshot.toJson(MAPPER));
        return job;
    }

    private static ConfigSnapshot snapshotWithScope(@Nullable FundingSource scope) {
        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                "openai-completions",
                FULL_URL,
                "gpt-5",
                null,
                null,
                null,
                false,
                scope,
                scope != null ? 42L : null,
                null,
                null,
                600,
                false,
                null,
                null);
    }
}
