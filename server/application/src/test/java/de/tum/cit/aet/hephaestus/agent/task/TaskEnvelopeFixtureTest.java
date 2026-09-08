package de.tum.cit.aet.hephaestus.agent.task;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** JSON contract fixture shared with the TypeScript runner tests. */
class TaskEnvelopeFixtureTest extends BaseUnitTest {

    private static final String FIXTURE_PATH = "task-fixtures/v2/practice-review.json";

    @Test
    void matchesFixture() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        TaskEnvelopeWriter writer = new TaskEnvelopeWriter(mapper);

        TaskEnvelope envelope = new TaskEnvelope(
                TaskEnvelope.SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-00000000abcd"),
                99L,
                new Task.PracticeReview(
                        "Review merge request #42 in owner/repo. Read the context files, "
                                + "then persist every justified observation via the report_observation tool. "
                                + "Follow .pi/AGENTS.md for the schema and rules.",
                        42,
                        "owner/repo"),
                TaskPaths.capturedInputs());

        String actual = writer.writeAsString(envelope);

        if ("true".equals(System.getProperty("hephaestus.snapshot.regenerate"))) {
            Files.writeString(resolveFixturePath(), actual, StandardCharsets.UTF_8);
            return;
        }

        String expected = readFixture();
        assertThat(mapper.readTree(actual)).isEqualTo(mapper.readTree(expected));
    }

    private static String readFixture() throws IOException {
        try (var is = TaskEnvelopeFixtureTest.class.getClassLoader().getResourceAsStream(FIXTURE_PATH)) {
            assertThat(is).as("classpath fixture %s", FIXTURE_PATH).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path resolveFixturePath() {
        Path candidate = Path.of("src/test/resources").resolve(FIXTURE_PATH);
        return Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/test/resources").resolve(FIXTURE_PATH);
    }
}
