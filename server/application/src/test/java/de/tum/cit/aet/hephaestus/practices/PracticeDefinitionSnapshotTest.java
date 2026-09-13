package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class PracticeDefinitionSnapshotTest extends BaseUnitTest {

    @Test
    void fingerprintsDetectionContentWithoutRecordingIt() throws Exception {
        Practice practice = new Practice();
        practice.setSlug("focused-reviews");
        practice.setName("Focused reviews");
        practice.setBindings(
                PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_REVIEWED, ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria("abc");
        practice.setPrecomputeScript("console.log('x')");
        practice.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.1.0"),
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null));
        practice.setWhyItMatters("Keeps feedback useful.");
        practice.setWhatGoodLooksLike("Each comment addresses one concern.");

        PracticeDefinitionSnapshot snapshot = PracticeDefinitionSnapshot.of(practice, 3);
        String json = JsonMapper.builder().build().writeValueAsString(snapshot);

        assertThat(snapshot.criteriaSha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(snapshot.precomputeScriptSha256())
                .isEqualTo("93f0d05c1fdeaf00615a94221cd849ea93ce5a5d19e130931fc5766637a21bb3");
        assertThat(snapshot.bindings())
                .singleElement()
                .satisfies(binding -> assertThat(binding.signals())
                        .containsExactly(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED));
        assertThat(json).doesNotContain("abc", "console.log");
    }
}
