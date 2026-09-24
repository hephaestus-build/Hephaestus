package de.tum.cit.aet.hephaestus.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TaskEnvelopeWriterTest extends BaseUnitTest {

    private TaskEnvelopeWriter writer;
    private JsonMapper reader;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder().build();
        writer = new TaskEnvelopeWriter(mapper);
        reader = mapper;
    }

    private TaskEnvelope sampleEnvelope() {
        return TaskEnvelope.of(
                UUID.fromString("00000000-0000-0000-0000-00000000abcd"),
                99L,
                new Task.PracticeReview("Review this PR", 42, "owner/repo"));
    }

    @Test
    void writesKindAndSchemaVersion() throws Exception {
        JsonNode root = reader.readTree(writer.write(sampleEnvelope()));

        assertThat(root.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(root.get("jobId").asString()).isEqualTo("00000000-0000-0000-0000-00000000abcd");
        assertThat(root.get("workspaceId").asLong()).isEqualTo(99L);
        assertThat(root.get("task").get("kind").asString()).isEqualTo("practice_review");
        assertThat(root.get("task").get("prompt").asString()).isEqualTo("Review this PR");
        assertThat(root.get("task").get("pullRequestNumber").asInt()).isEqualTo(42);
        assertThat(root.get("task").get("repositoryFullName").asString()).isEqualTo("owner/repo");
    }

    @Test
    void schemaVersionOnEnvelopeOnly() throws Exception {
        JsonNode root = reader.readTree(writer.write(sampleEnvelope()));
        assertThat(root.get("task").has("schemaVersion")).isFalse();
    }

    @Test
    void deterministicOutput() {
        TaskEnvelope env = sampleEnvelope();
        assertThat(writer.write(env)).isEqualTo(writer.write(env));
    }

    @Test
    @DisplayName("round-trips through Jackson with the @JsonSubTypes contract")
    void roundTripDeserialise() throws Exception {
        TaskEnvelope decoded = reader.readValue(writer.write(sampleEnvelope()), TaskEnvelope.class);

        assertThat(decoded.schemaVersion()).isEqualTo(2);
        assertThat(decoded.workspaceId()).isEqualTo(99L);
        assertThat(decoded.paths()).isEqualTo(TaskPaths.capturedInputs());
        assertThat(decoded.task()).isInstanceOf(Task.PracticeReview.class);
        Task.PracticeReview task = (Task.PracticeReview) decoded.task();
        assertThat(task.prompt()).isEqualTo("Review this PR");
        assertThat(task.pullRequestNumber()).isEqualTo(42);
        assertThat(task.repositoryFullName()).isEqualTo("owner/repo");
    }

    @Test
    void rejectsNonPositivePrNumber() {
        assertThatThrownBy(() -> new Task.PracticeReview("p", 0, "o/r")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Task.PracticeReview("p", -1, "o/r")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> new Task.PracticeReview("", 42, "owner/repo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveFields() {
        Task.PracticeReview task = new Task.PracticeReview("p", 1, "o/r");
        UUID jobId = UUID.randomUUID();
        assertThatThrownBy(() -> new TaskEnvelope(0, jobId, 1L, task, TaskPaths.capturedInputs()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> new TaskEnvelope(2, jobId, 0L, task, TaskPaths.capturedInputs()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    void ofUsesCurrentSchemaVersion() {
        TaskEnvelope env = TaskEnvelope.of(UUID.randomUUID(), 1L, new Task.PracticeReview("p", 1, "o/r"));
        assertThat(env.schemaVersion()).isEqualTo(TaskEnvelope.SCHEMA_VERSION);
    }
}
