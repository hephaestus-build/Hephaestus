package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The research question ("do developers' practices change over time?") is unanswerable unless the SAME
 * underlying finding gets the SAME identity across separate detection runs. A title-anchored key never
 * correlates because the LLM re-words every run, so identity is the {@code (practice, artifact, subject,
 * file)} LOCUS, not the prose. These cases lock that grain and what must NEVER perturb it.
 */
class ObservationFingerprintTest extends BaseUnitTest {

    private static final String SLUG = "ships-tests-with-the-change";
    private static final String TYPE = "scm.pull_request";

    @Test
    @DisplayName("identical locus → identical 64-char key (deterministic across runs)")
    void deterministic() {
        String a = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "Foo.swift");
        String b = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "Foo.swift");
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the file PATH is part of identity; a different file is a different finding")
    void pathParticipates() {
        String foo = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "Foo.swift");
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "Bar.swift"))
                .isNotEqualTo(foo);
    }

    @Test
    @DisplayName("a different practice / target / subject is a different finding")
    void discriminators() {
        String base = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "F.swift");
        assertThat(ObservationFingerprint.compute("other-practice", TYPE, 42L, 7L, "F.swift"))
                .isNotEqualTo(base);
        assertThat(ObservationFingerprint.compute(SLUG, "scm.issue", 42L, 7L, "F.swift"))
                .isNotEqualTo(base);
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 99L, 7L, "F.swift"))
                .isNotEqualTo(base);
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 8L, "F.swift"))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("a metadata practice (null path) correlates by (practice, target, subject) and is stable")
    void nullPathStable() {
        String n1 = ObservationFingerprint.compute("mr-description-quality", TYPE, 42L, 7L, null);
        String n2 = ObservationFingerprint.compute("mr-description-quality", TYPE, 42L, 7L, null);
        assertThat(n1).isEqualTo(n2).hasSize(64);
        assertThat(n1).isNotEqualTo(ObservationFingerprint.compute("mr-description-quality", TYPE, 42L, 7L, "F.swift"));
    }

    @Test
    void shouldKeepDistinctPathsSeparateWhenCaseOrWhitespaceDiffers() {
        String canonical = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/foo.swift");
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "SRC/Foo.swift"))
                .isNotEqualTo(canonical);
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/foo.swift "))
                .isNotEqualTo(canonical);
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/  foo.swift"))
                .isNotEqualTo(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/ foo.swift"));
    }

    @Test
    @DisplayName("adjacent fields cannot bleed: a shifted boundary between them yields a different key")
    void fieldBoundariesAreSeparated() {
        // The SEP separator is the invariant that stops adjacent fields from concatenating ambiguously.
        // Were it dropped, these pairs (which differ ONLY in where the boundary falls) would collide and
        // silently re-identify distinct findings. Lock that the separator keeps them apart.
        assertThat(ObservationFingerprint.compute("a-b", "PR", 1L, 1L, "F"))
                .as("slug 'a-b' + type 'PR' must not collide with slug 'a' + type 'b-PR'")
                .isNotEqualTo(ObservationFingerprint.compute("a", "b-PR", 1L, 1L, "F"));
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 1L, 23L, "F"))
                .as("artifactId 1 + aboutUserId 23 must not collide with artifactId 12 + aboutUserId 3")
                .isNotEqualTo(ObservationFingerprint.compute(SLUG, TYPE, 12L, 3L, "F"));
    }

    @Test
    void shouldPreserveControlCharactersWhenTheyDistinguishPaths() {
        String canonical = ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/foo.swift");
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "src/foo\u001f.swift"))
                .isNotEqualTo(canonical);
    }

    @Test
    @DisplayName("required args fail fast at the guard, not deep in the digest builder")
    void requiredArgsFailFast() {
        assertThatThrownBy(() -> ObservationFingerprint.compute(null, TYPE, 1L, 1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("practiceSlug");
        assertThatThrownBy(() -> ObservationFingerprint.compute(SLUG, null, 1L, 1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("artifactKind");
    }

    @Test
    @DisplayName("golden vector: the canonical digest is pinned so the wire identity never drifts silently")
    void goldenVector() {
        // Pin the exact path bytes and typed-field boundaries.
        assertThat(ObservationFingerprint.compute(SLUG, TYPE, 42L, 7L, "Foo.swift"))
                .isEqualTo("1cdf7c6a4a56d0199870d49a49ea7e9da77eee3de5ed811f69221bed788107c2");
    }
}
