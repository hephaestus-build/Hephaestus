package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackDraftBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

/**
 * A practice page card states the item count of its pattern, as the composer builds the card: it cites only this run's
 * result, and the earlier occurrences come from the support history (#5469).
 */
class PracticePageCountTest extends BaseUnitTest {
    private static final String PRACTICE = "keeps-views-free-of-networking-and-persistence";

    @Test
    void shouldLetAComposedPracticePageStateTheTwoItemsThatItsPatternSpans() throws Exception {
        var build = compose("Your views request and decode data across these two menu changes.");

        assertThatCode(build).doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseAComposedPracticePageThatCountsOneItemForATwoItemPattern() throws Exception {
        var build = compose("Your views request and decode data in 1 menu change.");

        assertThatThrownBy(build).isInstanceOf(FeedbackTextRefusedException.class);
    }

    /** The composer's own checks and packaging, then the draft builder with the rows that preparation reads. */
    private static ThrowingCallable compose(String body) throws Exception {
        var two = CompositionEvalCases.privateScenarios(CompositionEvalCases.load()).stream()
                .filter(each -> each.id().equals("gh-a-01-menu-view-does-its-own-networking/PRACTICE_PAGE/2"))
                .findFirst()
                .orElseThrow();
        String current = two.admission().results().get(0).path("resultId").asString();
        String prior = Objects.requireNonNull(two.admission().practicePageSupport())
                .path("practices")
                .get(0)
                .path("occurrences")
                .get(1)
                .path("resultId")
                .asString();
        var raw = JSON.objectNode();
        var piece = raw.putArray("feedback")
                .addObject()
                .put("destination", "PRACTICE_PAGE")
                .put("practiceSlug", PRACTICE)
                .put("action", "NEW")
                .put("title", "Data access inside views")
                .put("body", body)
                .put("nextStep", "Put the request and decoding in a model or store and call it from the view.");
        var basedOn = piece.putArray("basedOn");
        basedOn.add(current);
        // The composer cannot cite the earlier occurrence: basedOn takes only this run's admitted ids.
        var withPrior = raw.deepCopy();
        ((ArrayNode) withPrior.path("feedback").get(0).path("basedOn")).add(prior);
        assertThat(draft(two).call(withPrior).text()).contains("does not name an admitted result");

        // The evaluation accepts this card, with the support count as the verified count.
        if (body.contains("two")) assertThat(two.check(raw).accepted()).isTrue();

        var draft = draft(two);
        draft.call(raw);
        assertThat(draft.feedback).hasSize(1);
        var request = CompositionPackages.pieces(draft.feedback, List.of(), ignored -> current);

        var candidate = mock(ResultCandidate.class);
        lenient().when(candidate.developerId()).thenReturn(5L);
        lenient().when(candidate.practiceId()).thenReturn(31L);
        lenient().when(candidate.itemId()).thenReturn(7L);
        lenient().when(candidate.evidence()).thenReturn(new ResultEvidence(List.of(), null, null, List.of()));
        var earlier = mock(ResultHistoryEntry.class);
        lenient().when(earlier.resultId()).thenReturn(UUID.fromString(prior));
        lenient().when(earlier.developerAccountId()).thenReturn(5L);
        lenient().when(earlier.practiceId()).thenReturn(31L);
        lenient().when(earlier.itemId()).thenReturn(6L);
        var admitted = List.of(new AdmittedResultSnapshot(UUID.fromString(current), candidate, List.of()));
        return () ->
                FeedbackDraftBuilder.build(RuntimeReviewTestData.job(), request, admitted, List.of(earlier), Map.of());
    }

    private static PrivateFeedback.Draft draft(CompositionEvalCases.PrivateScenario scenario) {
        var slugs = Evidence.notMetPractices(scenario.admission().results());
        return new PrivateFeedback.Draft(
                new ArrayList<>(slugs),
                scenario.request(),
                scenario.admission(),
                List.of(),
                Map.of(),
                scenario.routes());
    }
}
