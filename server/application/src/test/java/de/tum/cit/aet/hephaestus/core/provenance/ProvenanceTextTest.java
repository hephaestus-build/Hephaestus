package de.tum.cit.aet.hephaestus.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ProvenanceTextTest extends BaseUnitTest {

    @Test
    void shouldHashTheSameWordsAlikeWhateverTheirLineEndsTrailingSpacesAndComposition() {
        String stored = "Add a **How to verify** section.\n\nCafé queue";
        String fromAnotherSystem = "  Add a **How to verify** section.  \r\n\r\nCafé queue\t\n";

        assertThat(ProvenanceText.sha256(fromAnotherSystem)).isEqualTo(ProvenanceText.sha256(stored));
    }

    @Test
    void shouldHashChangedWordsDifferently() {
        assertThat(ProvenanceText.sha256("Add a section.")).isNotEqualTo(ProvenanceText.sha256("Add no section."));
        // Inner spacing is part of the words; only line ends, trailing and outer space are not.
        assertThat(ProvenanceText.sha256("a  b")).isNotEqualTo(ProvenanceText.sha256("a b"));
    }
}
