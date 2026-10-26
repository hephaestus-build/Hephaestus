package de.tum.cit.aet.hephaestus.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TaskEnvelopeWriterTest extends BaseUnitTest {
    private final JsonMapper mapper = new JsonMapper();
    private final TaskEnvelopeWriter writer = new TaskEnvelopeWriter(mapper);
    private final UUID reviewId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Test
    void roundTripsReviewJobAttemptAndDecimalWorkspaceIdentity() {
        var envelope = TaskEnvelope.of(reviewId, jobId, 0, Long.MAX_VALUE, new Task("Review the pinned item"));
        var wire = mapper.readTree(writer.write(envelope));
        assertThat(wire.path("schemaVersion").asInt()).isEqualTo(7);
        assertThat(wire.path("reviewId").asString()).isEqualTo(reviewId.toString());
        assertThat(wire.path("jobId").asString()).isEqualTo(jobId.toString());
        assertThat(wire.path("workspaceId").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(wire.path("attempt").asInt()).isZero();
        assertThat(wire.has("pullRequestNumber")).isFalse();
        assertThat(wire.has("repositoryFullName")).isFalse();
        assertThat(mapper.readValue(writer.write(envelope), TaskEnvelope.class)).isEqualTo(envelope);
    }

    @Test
    void rejectsUnownedAttemptAndInvalidWorkspaceIdentity() {
        assertThatThrownBy(() -> TaskEnvelope.of(reviewId, jobId, -1, 1, new Task("Review")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskEnvelope.of(reviewId, jobId, 0, 0, new Task("Review")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankInstructions() {
        assertThatThrownBy(() -> new Task(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheRetiredTaskSchema() {
        var envelope = TaskEnvelope.of(reviewId, jobId, 0, 1, new Task("Review"));
        var wire = mapper.valueToTree(envelope);
        ((ObjectNode) wire).put("schemaVersion", 6);
        assertThatThrownBy(() -> mapper.treeToValue(wire, TaskEnvelope.class)).isInstanceOf(RuntimeException.class);
    }
}
