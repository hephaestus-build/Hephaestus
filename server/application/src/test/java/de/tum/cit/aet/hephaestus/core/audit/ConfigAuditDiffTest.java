package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code changed_keys} is what per-control history filters on, so its shape is an API contract, not an
 * implementation detail. Each row pins one property that a plausible wrong implementation would break.
 */
@Tag("unit")
class ConfigAuditDiffTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    static Stream<Arguments> diffs() {
        return Stream.of(
                // Returning every key rather than the changed ones would make the per-control filter match
                // every row and render the History panel useless.
                Arguments.of("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":3}", List.of("b"), "only the keys that differ"),
                // Drives no-op suppression: an idempotent PATCH must leave no row.
                Arguments.of("{\"a\":1}", "{\"a\":1}", List.of(), "identical snapshots yield no keys"),
                Arguments.of(
                        "{\"cooldownMinutes\":30}",
                        "{\"cooldownMinutes\":null}",
                        List.of("cooldownMinutes"),
                        "clearing an override back to inherit is a change"),
                // A top-level-only implementation would report "volumeCaps", leaving
                // changedKey=volumeCaps.perPullRequest matching nothing — the column's whole purpose.
                Arguments.of(
                        "{\"volumeCaps\":{\"perPullRequest\":5,\"perDay\":9}}",
                        "{\"volumeCaps\":{\"perPullRequest\":3,\"perDay\":9}}",
                        List.of("volumeCaps.perPullRequest"),
                        "a nested change yields the leaf path, not the container"),
                // Index paths would make the filter's value space depend on list order, which is not stable.
                Arguments.of(
                        "{\"slugs\":[\"a\",\"b\"]}",
                        "{\"slugs\":[\"a\",\"c\"]}",
                        List.of("slugs"),
                        "arrays compare whole rather than by index"),
                Arguments.of("{\"a\":1}", "{\"a\":1,\"b\":2}", List.of("b"), "a key on one side only is a change"),
                // Returning empty on create would let the recorder suppress it as a no-op, losing the event.
                Arguments.of(
                        null,
                        "{\"a\":1,\"b\":{\"c\":2}}",
                        List.of("a", "b.c"),
                        "create lists every leaf of the new state"),
                Arguments.of("{\"a\":1}", null, List.of("a"), "delete lists every leaf of the old state"),
                Arguments.of(
                        "{\"b\":1,\"a\":1}",
                        "{\"b\":2,\"a\":2}",
                        List.of("a", "b"),
                        "keys are sorted, so the stored array is stable"),
                // ConfigAuditRecorder drops an UPDATE whose diff is empty, so a credential snapshot built
                // only from presence flags would record nothing when a token that was already set is
                // rotated. The rotation instant is what keeps the row from being suppressed.
                Arguments.of(
                        "{\"tokenSet\":true,\"providerKind\":\"GITHUB\",\"rotatedAt\":null}",
                        "{\"tokenSet\":true,\"providerKind\":\"GITHUB\",\"rotatedAt\":\"2026-07-19T10:00:00Z\"}",
                        List.of("rotatedAt"),
                        "rotating an already-set credential still counts as a change"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("diffs")
    void reportsTheChangedKeys(@Nullable String before, @Nullable String after, List<String> expected, String why) {
        assertThat(ConfigAuditDiff.changedKeys(node(before), node(after)))
                .as(why)
                .containsExactlyElementsOf(expected);
    }

    private static @Nullable JsonNode node(@Nullable String json) {
        return json == null ? null : MAPPER.readTree(json);
    }
}
