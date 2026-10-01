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
                new Task("Review this PR", 42, "owner/repo"));
    }

    @Test
    void shouldWriteOneFlatRecordWithoutDiscriminator() throws Exception {
        JsonNode root = reader.readTree(writer.write(sampleEnvelope()));

        assertThat(root.get("schemaVersion").asInt()).isEqualTo(3);
        assertThat(root.get("jobId").asString()).isEqualTo("00000000-0000-0000-0000-00000000abcd");
        assertThat(root.get("workspaceId").asLong()).isEqualTo(99L);
        assertThat(root.has("kind")).isFalse();
        assertThat(root.has("task")).isFalse();
        assertThat(root.has("paths")).isFalse();
        root.properties()
                .forEach(field -> assertThat(field.getValue().isContainer()).isFalse());
        assertThat(root.get("prompt").asString()).isEqualTo("Review this PR");
        assertThat(root.get("pullRequestNumber").asInt()).isEqualTo(42);
        assertThat(root.get("repositoryFullName").asString()).isEqualTo("owner/repo");
    }

    @Test
    void schemaVersionOnEnvelopeOnly() throws Exception {
        JsonNode root = reader.readTree(writer.write(sampleEnvelope()));
        assertThat(root.get("schemaVersion").asInt()).isEqualTo(TaskEnvelope.SCHEMA_VERSION);
    }

    @Test
    void deterministicOutput() {
        TaskEnvelope env = sampleEnvelope();
        assertThat(writer.write(env)).isEqualTo(writer.write(env));
    }

    @Test
    @DisplayName("round-trips the flat record through Jackson")
    void roundTripDeserialise() throws Exception {
        TaskEnvelope decoded = reader.readValue(writer.write(sampleEnvelope()), TaskEnvelope.class);

        assertThat(decoded.schemaVersion()).isEqualTo(3);
        assertThat(decoded.workspaceId()).isEqualTo(99L);
        assertThat(decoded.paths()).isEqualTo(TaskPaths.capturedInputs());
        assertThat(decoded).isEqualTo(sampleEnvelope());
        assertThat(decoded.prompt()).isEqualTo("Review this PR");
        assertThat(decoded.pullRequestNumber()).isEqualTo(42);
        assertThat(decoded.repositoryFullName()).isEqualTo("owner/repo");
    }

    @Test
    void rejectsNonPositivePrNumber() {
        assertThatThrownBy(() -> new Task("p", 0, "o/r")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Task("p", -1, "o/r")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> new Task("", 42, "owner/repo")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveFields() {
        Task task = new Task("p", 1, "o/r");
        UUID jobId = UUID.randomUUID();
        assertThatThrownBy(() -> new TaskEnvelope(
                        0,
                        jobId,
                        1L,
                        "p",
                        1,
                        "o/r",
                        "context",
                        "repo",
                        "manifest.json",
                        "index.json",
                        "compose.json",
                        "prepared.json",
                        "precompute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> TaskEnvelope.of(jobId, 0L, task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    void ofUsesCurrentSchemaVersion() {
        TaskEnvelope env = TaskEnvelope.of(UUID.randomUUID(), 1L, new Task("p", 1, "o/r"));
        assertThat(env.schemaVersion()).isEqualTo(TaskEnvelope.SCHEMA_VERSION);
    }
}
