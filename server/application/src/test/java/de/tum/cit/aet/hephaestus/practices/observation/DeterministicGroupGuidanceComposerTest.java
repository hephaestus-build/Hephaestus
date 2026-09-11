package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingObservationDTO;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("unit")
class DeterministicGroupGuidanceComposerTest {

    @Test
    void shouldCombineStandingFocusAndCatalogExemplar() {
        PracticeStandingDTO gap = card(
                "tests",
                "Test Coverage",
                "Tests make changes safer.",
                "Cover new behaviour with focused tests.",
                List.of(item()),
                List.of());

        String guidance =
                DeterministicGroupGuidanceComposer.compose(PracticeGroupStandingDTO.Standing.DEVELOPING, List.of(gap));

        assertThat(guidance)
                .isEqualTo("Your recent feedback points to “Test Coverage” as the next practice to focus on. "
                        + "What good looks like: Cover new behaviour with focused tests.");
    }

    @Test
    void shouldAcknowledgeStrengthBeforeNamingTheNextFocus() {
        PracticeStandingDTO strength = card("reviews", "Actionable Reviews", null, null, List.of(), List.of(item()));
        PracticeStandingDTO gap = card("tests", "Test Coverage", null, null, List.of(item()), List.of());

        String guidance = DeterministicGroupGuidanceComposer.compose(
                PracticeGroupStandingDTO.Standing.MIXED, List.of(gap, strength));

        assertThat(guidance)
                .isEqualTo(
                        "Your recent feedback shows a strength in “Actionable Reviews”. Next, focus on “Test Coverage”.");
    }

    @Test
    void shouldNotNameAPracticeItsOwnStandingCallsFixedAsTheNextFocus() {
        // A clean streak has already moved this practice to STRENGTH. The older problems still on its card
        // must not name it as the group's next focus — the sentence would contradict the card beside it.
        PracticeStandingDTO fixed = new PracticeStandingDTO(
                "tests",
                "Test Coverage",
                "code-quality",
                "Code Quality",
                null,
                null,
                PracticeStandingDTO.Standing.STRENGTH,
                List.of(item()),
                List.of(item()),
                null,
                null);

        String guidance =
                DeterministicGroupGuidanceComposer.compose(PracticeGroupStandingDTO.Standing.STRENGTH, List.of(fixed));

        assertThat(guidance)
                .isEqualTo("Your recent feedback shows a strength in “Test Coverage”. Keep building on it.");
    }

    @ParameterizedTest
    @EnumSource(
            value = PracticeGroupStandingDTO.Standing.class,
            names = {"NOT_OBSERVED", "NO_OPPORTUNITY"})
    void shouldReturnNoGuidanceForEveryNonVerdictStatus(PracticeGroupStandingDTO.Standing status) {
        assertThat(DeterministicGroupGuidanceComposer.compose(status, List.of()))
                .isNull();
    }

    @Test
    void shouldKeepLongCatalogGuidanceCompactAndOnOneLine() {
        String longExemplar = ("Explain the motivation, constraints, rollout, alternatives, and expected outcome "
                        + "in language that lets a reviewer understand the decision without reconstructing it from code.\n")
                .repeat(3);
        PracticeStandingDTO gap =
                card("pr-descriptions", "PR Descriptions", null, longExemplar, List.of(item()), List.of());

        String guidance =
                DeterministicGroupGuidanceComposer.compose(PracticeGroupStandingDTO.Standing.DEVELOPING, List.of(gap));

        assertThat(guidance).doesNotContain("\n").endsWith("…").hasSizeLessThan(300);
    }

    private static PracticeStandingDTO card(
            String slug,
            String name,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            List<PracticeStandingObservationDTO> toWorkOn,
            List<PracticeStandingObservationDTO> strengths) {
        PracticeStandingDTO.Standing standing = !toWorkOn.isEmpty() && !strengths.isEmpty()
                ? PracticeStandingDTO.Standing.MIXED
                : !toWorkOn.isEmpty() ? PracticeStandingDTO.Standing.DEVELOPING : PracticeStandingDTO.Standing.STRENGTH;
        return new PracticeStandingDTO(
                slug,
                name,
                "code-quality",
                "Code Quality",
                whyItMatters,
                whatGoodLooksLike,
                standing,
                toWorkOn,
                strengths,
                null,
                null);
    }

    private static PracticeStandingObservationDTO item() {
        return new PracticeStandingObservationDTO(
                UUID.randomUUID(),
                "Observation",
                null,
                null,
                ObservationKind.OMISSION_GAP,
                ArtifactKinds.PULL_REQUEST,
                1L,
                null,
                ObservationOrigin.LIVE);
    }
}
