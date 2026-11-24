package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The published learning bundle schema, as the server JAR carries it, against the v1 fixture. */
class LearningBundleSchemaTest extends BaseUnitTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(resource("learning-bundle/learning-bundle-v1.schema.json"));

    @Test
    void shouldAcceptTheFixtureWhenItFollowsFormatVersionOne() {
        assertThat(SCHEMA.validate(fixture())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("refusedBundles")
    void shouldRefuseTheBundleWhenItLeavesTheFormat(Consumer<ObjectNode> change) {
        ObjectNode bundle = fixture();
        change.accept(bundle);

        List<Error> errors = SCHEMA.validate(bundle);

        assertThat(errors).isNotEmpty();
    }

    static Stream<Arguments> refusedBundles() {
        return Stream.of(
                refused("an unknown format version", b -> b.put("formatVersion", 2)),
                refused("no issuing instance", b -> b.remove("instanceId")),
                refused("the source workspace", b -> b.put("workspace", "client-a")),
                refused("a record that waits for review", b -> record(b).put("state", "NEEDS_REVIEW")),
                refused("a record without a revision number", b -> record(b).put("revision", 0)),
                refused("a practice of one workspace", b -> growthPoint(b).put("practiceKey", "local/42")),
                refused(
                        "the piece of work of a growth point",
                        b -> growthPoint(b).put("workRef", "github/acme/api#12")),
                refused("text in a tombstone", b -> tombstone(b).put("text", "deleted words")),
                refused("a time that is not UTC", b -> b.put("generatedAt", "2026-11-24T22:30:00+01:00")),
                refused("an id that is not a UUID", b -> b.put("bundleId", "bundle-1")));
    }

    private static Arguments refused(String name, Consumer<ObjectNode> change) {
        return Arguments.of(Named.of(name, change));
    }

    private static ObjectNode record(ObjectNode bundle) {
        return (ObjectNode) bundle.withArray("records").get(0);
    }

    private static ObjectNode growthPoint(ObjectNode bundle) {
        return (ObjectNode) bundle.withArray("growthPoints").get(0);
    }

    private static ObjectNode tombstone(ObjectNode bundle) {
        return (ObjectNode) bundle.withArray("tombstones").get(0);
    }

    private static ObjectNode fixture() {
        try (InputStream in = resource("learning-bundle/v1-fixture.json")) {
            return (ObjectNode) MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream resource(String path) {
        return Objects.requireNonNull(
                LearningBundleSchemaTest.class.getClassLoader().getResourceAsStream(path), path);
    }
}
