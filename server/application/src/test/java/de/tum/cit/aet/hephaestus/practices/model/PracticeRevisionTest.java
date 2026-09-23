package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PracticeRevisionTest extends BaseUnitTest {

    @Test
    void shouldSnapshotCompleteDefinitionAndGroup() {
        PracticeGroup group = new PracticeGroup();
        group.setSlug("review-quality");
        group.setName("Review quality");
        group.setDescription("Review work");
        group.setIcon("MessageSquare");
        group.setColor("cyan");
        Practice practice = new Practice();
        practice.setSlug("clear-feedback");
        practice.setName("Clear feedback");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria("Detect clear feedback");
        practice.setPrecomputeScript("export default {}");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setWhyItMatters("Prevents rework");
        practice.setWhatGoodLooksLike("A concrete suggestion");
        practice.setGroup(group);

        PracticeRevision revision = new PracticeRevision(practice, 3);
        // The revision copies the list; a later edit to the practice's bindings must not reach it.
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_REVIEWED));

        assertThat(revision.getRevisionNumber()).isEqualTo(3);
        assertThat(revision.getSlug()).isEqualTo("clear-feedback");
        assertThat(revision.getName()).isEqualTo("Clear feedback");
        assertThat(revision.getArtifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
        assertThat(revision.getBindings()).isEqualTo(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        assertThat(revision.getCriteria()).isEqualTo("Detect clear feedback");
        assertThat(revision.getPrecomputeScript()).isEqualTo("export default {}");
        assertThat(revision.getAutomatedReviewPolicy()).isEqualTo(practice.getAutomatedReviewPolicy());
        assertThat(revision.getWhyItMatters()).isEqualTo("Prevents rework");
        assertThat(revision.getWhatGoodLooksLike()).isEqualTo("A concrete suggestion");
        assertThat(revision)
                .extracting(
                        PracticeRevision::getGroupSlug,
                        PracticeRevision::getGroupName,
                        PracticeRevision::getGroupDescription,
                        PracticeRevision::getGroupIcon,
                        PracticeRevision::getGroupColor)
                .containsExactly("review-quality", "Review quality", "Review work", "MessageSquare", "cyan");
        assertThat(revision.getReviewRuleFingerprint()).hasSize(67).startsWith("v4:");
    }
}
