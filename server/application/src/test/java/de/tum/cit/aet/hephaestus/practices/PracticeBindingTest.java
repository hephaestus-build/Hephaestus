package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The occasion a practice is reviewed on, and the evidence that occasion reads.
 *
 * <p>The source list is digested into the review-rule fingerprint, so two authors writing the same thing
 * in a different order must not read as two different rules, and a source named twice has no answer to
 * which stance won.
 */
class PracticeBindingTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    @Test
    void shouldRejectASourceNamedTwiceWhateverTheStance() {
        assertThatThrownBy(() -> binding(List.of(required(CORE), required(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An evidence source is listed twice. List each source once.");
        assertThatThrownBy(() -> binding(List.of(required(CORE), contextual(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An evidence source is listed twice. List each source once.");
    }

    @Test
    void shouldCanonicalizeSourceOrder() {
        PracticeBinding binding = binding(List.of(required(DIFF), contextual(COMMENTS), required(CORE)));

        assertThat(binding.needs())
                .extracting(need -> need.sourceKind().value())
                .containsExactly("scm.pull-request.comments", "scm.pull-request.core", "scm.pull-request.diff");
    }

    @Test
    void shouldSortAndDeduplicateSignals() {
        PracticeBinding binding = new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_MERGED, ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_MERGED),
                List.of(required(CORE)),
                false);

        assertThat(binding.signals()).containsExactly(ScmSignals.PULL_REQUEST_MERGED, ScmSignals.PULL_REQUEST_OPENED);
    }

    @Test
    void shouldRefuseSignalsOfDifferentKindsInOneBinding() {
        assertThatThrownBy(() -> new PracticeBinding(
                        List.of(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.ISSUE_OPENED),
                        List.of(required(CORE)),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "The moments that start a review must all belong to one kind of work. Choose them from one.");
    }

    @Test
    void shouldRefuseAPracticeWhoseBindingsDisagreeAboutWhatItReviews() {
        assertThatThrownBy(() -> PracticeBinding.artifactKindOf(List.of(
                        PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE))),
                        PracticeBinding.on(ScmSignals.ISSUE_OPENED, List.of(required(CORE))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A practice reviews one kind of work. Choose its moments from one kind of work only.");
    }

    /**
     * Whether a draft occasions a review is a property of the binding, not of the workspace: judging how
     * a change was handed over is exactly what one wants to say about a draft, and a fleet-wide veto puts
     * such a practice out of reach of the only artifact it is about.
     */
    @Test
    void aDraftOccasionsOnlyTheBindingThatSaysSo() {
        PracticeBinding onFinished = PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE)));
        PracticeBinding onDrafts =
                new PracticeBinding(List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(required(CORE)), true);

        assertThat(onFinished.occasionedBy(ScmSignals.PULL_REQUEST_OPENED, false))
                .isTrue();
        assertThat(onFinished.occasionedBy(ScmSignals.PULL_REQUEST_OPENED, true))
                .isFalse();
        assertThat(onDrafts.occasionedBy(ScmSignals.PULL_REQUEST_OPENED, true)).isTrue();
        assertThat(onDrafts.occasionedBy(ScmSignals.PULL_REQUEST_MERGED, false)).isFalse();
    }

    /**
     * A review nobody occasioned reads everything, because a review somebody asked for is a request to
     * apply the practice as widely as it applies.
     */
    @Test
    void evidenceIsSelectedByTheOccasionAndUnionedWhenThereIsNone() {
        List<PracticeBinding> bindings = List.of(
                PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE))),
                PracticeBinding.on(ScmSignals.PULL_REQUEST_MERGED, List.of(required(DIFF))));

        assertThat(PracticeBinding.needsFor(bindings, ScmSignals.PULL_REQUEST_OPENED))
                .extracting(need -> need.sourceKind().value())
                .containsExactly("scm.pull-request.core");
        assertThat(PracticeBinding.needsFor(bindings, null))
                .extracting(need -> need.sourceKind().value())
                .containsExactly("scm.pull-request.core", "scm.pull-request.diff");
    }

    /**
     * The default lives in the reader, not the schema, because the component is a primitive: without it,
     * every bundled practice and client would have to write {@code "onDrafts": false}, and a shape that
     * must be written out is a shape that will be written out wrong.
     */
    @Test
    void aBindingThatSaysNothingAboutDraftsDoesNotRunOnThem() {
        JsonMapper mapper = JsonMapper.builder().build();

        PracticeBinding parsed = mapper.readValue(
                "{\"signals\": [\"scm.pull_request.opened\"], "
                        + "\"needs\": [{\"sourceKind\": \"scm.pull-request.core\", \"stance\": \"REQUIRED\"}]}",
                PracticeBinding.class);

        assertThat(parsed.onDrafts()).isFalse();
        assertThat(parsed.signals()).containsExactly(ScmSignals.PULL_REQUEST_OPENED);
        assertThat(mapper.readValue(mapper.writeValueAsString(parsed), PracticeBinding.class))
                .isEqualTo(parsed);
    }

    @Test
    void shouldReadABindingThatSaysDraftsOccasionIt() {
        JsonMapper mapper = JsonMapper.builder().build();

        PracticeBinding parsed = mapper.readValue(
                "{\"signals\": [\"scm.pull_request.opened\"], "
                        + "\"needs\": [{\"sourceKind\": \"scm.pull-request.core\", \"stance\": \"REQUIRED\"}], \"onDrafts\": true}",
                PracticeBinding.class);

        assertThat(parsed.onDrafts()).isTrue();
    }

    private static PracticeBinding binding(List<PracticeEvidenceRequirement> needs) {
        return PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, needs);
    }

    private static PracticeEvidenceRequirement required(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.REQUIRED);
    }

    private static PracticeEvidenceRequirement contextual(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.CONTEXTUAL);
    }
}
