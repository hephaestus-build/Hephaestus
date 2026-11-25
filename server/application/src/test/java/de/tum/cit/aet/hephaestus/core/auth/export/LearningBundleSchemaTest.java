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

    @Test
    void shouldAcceptARecordTextWhenItHoldsTabsAndLineBreaks() {
        ObjectNode bundle = fixture();
        record(bundle, 0).put("text", "First line\r\n\tindented second line\nthird line");

        assertThat(SCHEMA.validate(bundle)).isEmpty();
    }

    @Test
    void shouldAcceptARecordTextWhenItHoldsBidirectionalMarks() {
        ObjectNode bundle = fixture();
        record(bundle, 0).put("text", "\u05e9\u05dc\u05d5\u05dd \u200Fv2\u200E \u061Cok");

        assertThat(SCHEMA.validate(bundle)).isEmpty();
    }

    /** Each case changes one value of the valid fixture, so the named keyword at the named place is the refusal. */
    @ParameterizedTest
    @MethodSource("refusedBundles")
    void shouldRefuseTheBundleWhenItLeavesTheFormat(Consumer<ObjectNode> change, String keyword, String location) {
        ObjectNode bundle = fixture();
        change.accept(bundle);

        List<Error> errors = SCHEMA.validate(bundle);

        assertThat(errors).anySatisfy(error -> {
            assertThat(error.getKeyword()).isEqualTo(keyword);
            assertThat(error.getInstanceLocation().toString()).isEqualTo(location);
        });
    }

    static Stream<Arguments> refusedBundles() {
        return Stream.of(
                refused("an unknown format version", b -> b.put("formatVersion", 2), "const", "/formatVersion"),
                refused("no issuing instance", b -> b.remove("instanceId"), "required", ""),
                refused("the source workspace", b -> b.put("workspace", "client-a"), "additionalProperties", ""),
                refused("an id that is not a UUID", b -> b.put("bundleId", "bundle-1"), "pattern", "/bundleId"),
                refused(
                        "a time that is not UTC",
                        b -> b.put("generatedAt", "2026-11-24T22:30:00+01:00"),
                        "pattern",
                        "/generatedAt"),
                refused(
                        "a month that does not exist",
                        b -> b.put("generatedAt", "2026-13-01T00:00:00Z"),
                        "pattern",
                        "/generatedAt"),
                refused(
                        "a record that waits for review",
                        b -> record(b, 0).put("state", "NEEDS_REVIEW"),
                        "enum",
                        "/records/0/state"),
                refused(
                        "a revision number of zero",
                        b -> record(b, 0).put("revision", 0),
                        "minimum",
                        "/records/0/revision"),
                refused(
                        "a record without a revision number",
                        b -> record(b, 0).remove("revision"),
                        "required",
                        "/records/0"),
                refused(
                        "the source workspace of a record",
                        b -> record(b, 0).put("workspace", "client-a"),
                        "additionalProperties",
                        "/records/0"),
                refused(
                        "a revision number past the exact integer range of JSON readers",
                        b -> record(b, 0).put("revision", 9_007_199_254_740_992L),
                        "maximum",
                        "/records/0/revision"),
                refused(
                        "a closed record without its reason",
                        b -> record(b, 1).remove("closeReason"),
                        "required",
                        "/records/1"),
                refused(
                        "an active record with a close reason",
                        b -> record(b, 0).put("closeReason", "DROPPED"),
                        "not",
                        "/records/0"),
                refused(
                        "a help preference on a record that is not a goal",
                        b -> record(b, 0).put("helpPreference", "HINT"),
                        "not",
                        "/records/0"),
                refused(
                        "a NUL character in a record text",
                        b -> record(b, 0).put("text", "before\u0000after"),
                        "pattern",
                        "/records/0/text"),
                refused(
                        "a year before 1000",
                        b -> b.put("generatedAt", "0000-01-01T00:00:00Z"),
                        "pattern",
                        "/generatedAt"),
                refused(
                        "an author kind that the format does not know",
                        b -> record(b, 0).put("authorKind", "HEPHAESTUS"),
                        "enum",
                        "/records/0/authorKind"),
                refused(
                        "a bidirectional override in a record text",
                        b -> record(b, 0).put("text", "before\u202Eafter"),
                        "pattern",
                        "/records/0/text"),
                refused(
                        "a record text past its length",
                        b -> record(b, 0).put("text", "a".repeat(8001)),
                        "maxLength",
                        "/records/0/text"),
                refused(
                        "one practice named twice",
                        b -> record(b, 0)
                                .putArray("practiceKeys")
                                .add("software-engineering/atomic-commits")
                                .add("software-engineering/atomic-commits"),
                        "uniqueItems",
                        "/records/0/practiceKeys"),
                refused(
                        "an origin on a point that was not imported",
                        b -> growthPoint(b, 0).set("origin", growthPoint(b, 2).get("origin")),
                        "not",
                        "/growthPoints/0"),
                refused(
                        "a week that no year has",
                        b -> growthPoint(b, 0).put("occurredWeek", "2026-W54"),
                        "pattern",
                        "/growthPoints/0/occurredWeek"),
                refused(
                        "a tombstone item type that the format does not know",
                        b -> tombstone(b).put("itemType", "PORTABLE_RECORD"),
                        "enum",
                        "/tombstones/0/itemType"),
                refused(
                        "the time of a tombstone",
                        b -> tombstone(b).put("at", "2026-11-18T09:05:00Z"),
                        "additionalProperties",
                        "/tombstones/0"),
                refused(
                        "a tombstone origin without its portable id",
                        b -> ((ObjectNode) b.withArray("tombstones").get(1).get("origin")).remove("portableId"),
                        "required",
                        "/tombstones/1/origin"),
                refused(
                        "a tombstone reason that the format does not know",
                        b -> tombstone(b).put("reason", "EXPIRED"),
                        "enum",
                        "/tombstones/0/reason"),
                refused(
                        "an escape character in a record text",
                        b -> record(b, 0).put("text", "red \u001B[31m text"),
                        "pattern",
                        "/records/0/text"),
                refused(
                        "a bidirectional isolate in a record text",
                        b -> record(b, 0).put("text", "before\u2066after"),
                        "pattern",
                        "/records/0/text"),
                refused(
                        "a record without its goal links",
                        b -> record(b, 0).remove("goalIds"),
                        "required",
                        "/records/0"),
                refused(
                        "a goal link that is not a UUID",
                        b -> record(b, 0).putArray("goalIds").add("goal-1"),
                        "pattern",
                        "/records/0/goalIds/0"),
                refused(
                        "one goal linked twice",
                        b -> record(b, 0)
                                .putArray("goalIds")
                                .add("c3a5e7f9-2b4d-4a6c-8e0f-1b3d5f7a9c02")
                                .add("c3a5e7f9-2b4d-4a6c-8e0f-1b3d5f7a9c02"),
                        "uniqueItems",
                        "/records/0/goalIds"),
                refused(
                        "a goal linked to a goal",
                        b -> record(b, 1).putArray("goalIds").add("b2f4c6d8-1a3e-4f5b-9c7d-0e2a4c6e8f01"),
                        "maxItems",
                        "/records/1/goalIds"),
                refused(
                        "a practice of one workspace",
                        b -> growthPoint(b, 0).put("practiceKey", "local/42"),
                        "not",
                        "/growthPoints/0/practiceKey"),
                refused(
                        "the stored key of a practice of one workspace",
                        b -> growthPoint(b, 0).put("practiceKey", "local/42/12"),
                        "pattern",
                        "/growthPoints/0/practiceKey"),
                refused(
                        "an id in upper case",
                        b -> b.put("instanceId", "0C9E8D7F-6A5B-4C3D-8E2F-1A0B9C8D7E6F"),
                        "pattern",
                        "/instanceId"),
                refused(
                        "the piece of work of a growth point",
                        b -> growthPoint(b, 0).put("workRef", "github/acme/api#12"),
                        "additionalProperties",
                        "/growthPoints/0"),
                refused(
                        "the comparability class of a workspace's own practice text",
                        b -> growthPoint(b, 0).put("comparabilityClass", "v1:" + "a".repeat(64)),
                        "additionalProperties",
                        "/growthPoints/0"),
                refused(
                        "an imported growth point without its origin",
                        b -> growthPoint(b, 2).remove("origin"),
                        "required",
                        "/growthPoints/2"),
                refused(
                        "text in a tombstone",
                        b -> tombstone(b).put("text", "deleted words"),
                        "additionalProperties",
                        "/tombstones/0"),
                refused(
                        "a tombstone without its reason",
                        b -> tombstone(b).remove("reason"),
                        "required",
                        "/tombstones/0"));
    }

    private static Arguments refused(String name, Consumer<ObjectNode> change, String keyword, String location) {
        return Arguments.of(Named.of(name, change), keyword, location);
    }

    private static ObjectNode record(ObjectNode bundle, int index) {
        return (ObjectNode) bundle.withArray("records").get(index);
    }

    private static ObjectNode growthPoint(ObjectNode bundle, int index) {
        return (ObjectNode) bundle.withArray("growthPoints").get(index);
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
