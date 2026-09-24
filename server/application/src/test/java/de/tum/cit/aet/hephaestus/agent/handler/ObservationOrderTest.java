package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ordering four stages used to duplicate, and used to tiebreak on the detector's self-reported
 * confidence. These pin what replaced it: severity where it applies, then how much of the corpus the
 * observation's citations actually span, then a stable identity so the order is total.
 */
class ObservationOrderTest extends BaseUnitTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Evidence citing {@code loci} distinct places, plus {@code repeats} extra copies of the first one. */
    private static ObjectNode evidence(int loci, int repeats) {
        ObjectNode evidence = MAPPER.createObjectNode();
        var citations = evidence.putArray("citations");
        for (int i = 0; i < loci; i++) {
            citations
                    .addObject()
                    .put("sourceKind", "scm.pull-request.diff")
                    .put("path", "src/File" + i + ".java")
                    .put("side", "NEW")
                    .put("startLine", 10)
                    .put("endLine", 10);
        }
        for (int i = 0; i < repeats; i++) {
            citations
                    .addObject()
                    .put("sourceKind", "scm.pull-request.diff")
                    .put("path", "src/File0.java")
                    .put("side", "NEW")
                    .put("startLine", 10)
                    .put("endLine", 10);
        }
        return evidence;
    }

    private static ValidatedObservation observation(String title, @Nullable Severity severity, int loci) {
        return new ValidatedObservation(
                "slug",
                title,
                AssessmentStatus.ASSESSED,
                severity == null ? Presence.ABSENT : Presence.PRESENT,
                severity == null ? Assessment.GOOD : Assessment.BAD,
                severity,
                evidence(loci, 0),
                "reasoning");
    }

    @Test
    @DisplayName("evidence breadth counts distinct loci, so a repeated quote buys nothing")
    void breadthCountsDistinctLoci() {
        // The lever confidence used to be was one the model could pull at will. Counting citations rather
        // than distinct places would hand the same lever back: quote one line four times and lead the list.
        assertThat(ObservationOrder.evidenceBreadth(evidence(3, 0))).isEqualTo(3);
        assertThat(ObservationOrder.evidenceBreadth(evidence(3, 5))).isEqualTo(3);
        assertThat(ObservationOrder.evidenceBreadth(evidence(0, 0))).isZero();
        // An observation whose evidence we cannot read scores 0 and sorts last in its band — we cannot see what
        // it rests on, so it does not get to lead.
        assertThat(ObservationOrder.evidenceBreadth(null)).isZero();
        assertThat(ObservationOrder.evidenceBreadth(MAPPER.createObjectNode())).isZero();
    }

    @Test
    @DisplayName("severity leads, breadth breaks the tie, identity makes the order total")
    void worstFirstRanksSeverityThenBreadth() {
        ValidatedObservation majorNarrow = observation("major narrow", Severity.MAJOR, 1);
        ValidatedObservation majorWide = observation("major wide", Severity.MAJOR, 4);
        ValidatedObservation criticalNarrow = observation("critical narrow", Severity.CRITICAL, 1);
        ValidatedObservation minorWide = observation("minor wide", Severity.MINOR, 9);

        List<ValidatedObservation> sorted = new ArrayList<>(List.of(minorWide, majorNarrow, criticalNarrow, majorWide));
        sorted.sort(ObservationOrder.worstFirstUnstored());

        // Breadth never outranks severity: the widest MINOR still sorts below the narrowest MAJOR, because
        // proportionality favours the more consequential lesson.
        assertThat(sorted.stream().map(ValidatedObservation::summary))
                .containsExactly("critical narrow", "major wide", "major narrow", "minor wide");
    }

    @Test
    @DisplayName("the order is total and reproducible for two observations that differ in nothing rankable")
    void tiesResolveDeterministically() {
        ValidatedObservation a = observation("aaa", Severity.MINOR, 2);
        ValidatedObservation b = observation("bbb", Severity.MINOR, 2);

        // Both directions of the input produce the same output — this is the property the confidence
        // tiebreak was there for, and the only one it actually delivered.
        List<ValidatedObservation> one = new ArrayList<>(List.of(a, b));
        List<ValidatedObservation> other = new ArrayList<>(List.of(b, a));
        one.sort(ObservationOrder.worstFirstUnstored());
        other.sort(ObservationOrder.worstFirstUnstored());

        assertThat(one.stream().map(ValidatedObservation::summary)).containsExactly("aaa", "bbb");
        assertThat(other.stream().map(ValidatedObservation::summary)).containsExactly("aaa", "bbb");
    }

    @Test
    @DisplayName("strengths rank on breadth alone, since none of them carries a severity")
    void strengthsRankOnBreadthAlone() {
        ValidatedObservation narrow = observation("narrow strength", null, 1);
        ValidatedObservation wide = observation("wide strength", null, 5);

        List<ValidatedObservation> sorted = new ArrayList<>(List.of(narrow, wide));
        sorted.sort(ObservationOrder.bestAttestedFirst());

        assertThat(sorted.stream().map(ValidatedObservation::summary))
                .containsExactly("wide strength", "narrow strength");
        // Every one of them has a null severity, so including severity would advertise a dimension that does
        // not exist here. Ranking must still be strict.
        assertThat(sorted.get(0).severity()).isNull();
    }

    @Test
    @DisplayName("persisted rows fall back to their id, so the ledger's ordinal survives a re-run")
    void persistedRowsTiebreakOnId() {
        var earlier = de.tum.cit.aet.hephaestus.practices.model.Observation.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(Assessment.BAD)
                .severity(Severity.MINOR)
                .evidence(evidence(1, 0))
                .build();
        var later = de.tum.cit.aet.hephaestus.practices.model.Observation.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(Assessment.BAD)
                .severity(Severity.MINOR)
                .evidence(evidence(1, 0))
                .build();

        var sorted = new ArrayList<>(List.of(later, earlier));
        sorted.sort(ObservationOrder.worstFirst());

        assertThat(sorted.get(0).getId()).isEqualTo(earlier.getId());
    }
}
