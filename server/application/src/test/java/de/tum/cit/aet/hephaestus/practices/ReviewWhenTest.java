package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.PullRequestArtifactDescriptor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ReviewWhenTest extends BaseUnitTest {
    @Test
    void unrestrictedPolicyDoesNotRequireStateFacts() {
        assertThat(ReviewWhen.matches(Map.of(), Map.of())).isTrue();
    }

    @Test
    void alternativesWithinEachDimensionStillRequireEverySelectedDimension() {
        var policy = Map.of("state", Set.of("OPEN", "CLOSED"), "draftStatus", Set.of("NOT_DRAFT"));
        assertThat(ReviewWhen.matches(policy, Map.of("state", "OPEN", "draftStatus", "NOT_DRAFT")))
                .isTrue();
        assertThat(ReviewWhen.matches(policy, Map.of("state", "CLOSED", "draftStatus", "NOT_DRAFT")))
                .isTrue();
        assertThat(ReviewWhen.matches(policy, Map.of("state", "MERGED", "draftStatus", "NOT_DRAFT")))
                .isFalse();
        assertThat(ReviewWhen.matches(policy, Map.of("state", "OPEN", "draftStatus", "DRAFT")))
                .isFalse();
        assertThat(ReviewWhen.matches(policy, Map.of("state", "OPEN"))).isFalse();
    }

    @Test
    void completeSelectionsAreUnrestrictedButNewPullRequestsExcludeDraftsByDefault() {
        var dimensions =
                new PullRequestArtifactDescriptor().reviewCapabilities().reviewWhenDimensions();
        assertThat(ReviewWhen.recommended(dimensions))
                .containsExactlyEntriesOf(Map.of("draftStatus", Set.of("NOT_DRAFT")));
        assertThat(ReviewWhen.normalize(
                        Map.of(
                                "draftStatus",
                                Set.of("DRAFT", "NOT_DRAFT"),
                                "state",
                                Set.of("OPEN", "CLOSED", "MERGED")),
                        dimensions))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"state\":null}", "{\"state\":[null]}"})
    void rejectsNullSelectionsReadFromJson(String json) throws Exception {
        Map<String, Set<String>> policy = JsonMapper.builder().build().readValue(json, new TypeReference<>() {});
        assertThatThrownBy(() -> ReviewWhen.canonical(policy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Review state selections and values must not be null: state");
    }

    @Test
    void rejectsAnEmptySelectionInsteadOfTreatingItAsUnrestricted() {
        assertThatThrownBy(() -> ReviewWhen.canonical(Map.of("state", Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Review state selections must not be empty: state");
    }
}
