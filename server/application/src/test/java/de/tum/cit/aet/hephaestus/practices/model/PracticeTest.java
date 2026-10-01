package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PracticeTest extends BaseUnitTest {

    /** {@code artifactKind} is a projection of the signals, so setting signals must re-derive it. */
    @Test
    void settingBindingsIsTheOnlyThingThatDecidesTheArtifactKind() {
        Practice practice = new Practice();
        assertThat(practice.getArtifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);

        PracticeTestEvidence.configure(practice, ArtifactKinds.CONVERSATION_THREAD);
        assertThat(practice.getArtifactKind()).isEqualTo(ArtifactKinds.CONVERSATION_THREAD);

        PracticeTestEvidence.configure(practice, ArtifactKinds.ISSUE);
        assertThat(practice.getArtifactKind()).isEqualTo(ArtifactKinds.ISSUE);
    }
}
