package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceFileReads;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe.Presence;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The proof of No longer present for one cited text: the capture must hold the path, and no text may hold the quote. */
class CapturedQuoteProbeTest extends BaseUnitTest {
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final String QUOTE = "if (user == null) return;";

    private final ReviewJob job = TestEntities.reviewJob();
    private final EvidenceFileReads files = mock(EvidenceFileReads.class);

    private static EvidenceCitation cited(String path) {
        return new EvidenceCitation(
                DIFF, "inputs/sources/scm/diff.patch", path, CitationSide.NEW, "old-head", 3, 3, QUOTE, null);
    }

    /** A complete capture of the change, with {@code texts} as path and side to content. */
    private CapturedQuoteProbe probe(SourceCompleteness completeness, Map<String, String> texts) {
        var citable = new ArrayList<CitableText>();
        texts.forEach((key, content) -> {
            var side = key.startsWith("old:") ? CitationSide.OLD : CitationSide.NEW;
            String path = key.substring(4);
            String staged = "context/citable/" + key.substring(0, 3) + "/" + path;
            citable.add(new CitableText(
                    DIFF.value(), path, side, side == CitationSide.OLD ? "base" : "head", staged, sha(staged)));
            when(files.readText(eq(job), eq(staged), any())).thenReturn(Optional.of(content));
        });
        // The capture of a change also stages its unified diff as a record, with no commit. Its removed lines hold
        // code that the change deletes, so it never counts as the work.
        String record = "context/citable-records/scm.pull-request.diff/change.json";
        citable.add(new CitableText(
                DIFF.value(), "context/change.json#/diff", CitationSide.NEW, null, record, sha(record)));
        when(files.readText(eq(job), eq(record), any())).thenReturn(Optional.of("-" + QUOTE + "\n+requireUser();"));
        var manifest = new JobFolderIndex(
                new SourceContractVersion("3.0.0"),
                "a".repeat(64),
                "scm.pull_request",
                Instant.now(),
                List.of(new SourceCapture(
                        DIFF,
                        new SourceCaptureState.Available(
                                SourceContentState.NON_EMPTY,
                                completeness,
                                new SourceCaptureFacts(Instant.now(), null, null, "base:head")),
                        EvidenceSnapshotFixtures.citation(DIFF),
                        List.of(new CapturedSource("context/change.json", "application/json", "b".repeat(64), 1)))),
                List.of(),
                List.of(),
                citable);
        return new CapturedQuoteProbe(job, CapturedEvidence.of(manifest), files);
    }

    private static String sha(String path) {
        return String.format("%064x", path.hashCode() & 0xffffffffL);
    }

    /** A quote of the old side cites what the change removes: its absence from the new side repairs nothing. */
    @Test
    void shouldNotSayForAQuoteOfTheOldSide() {
        var probe = probe(
                SourceCompleteness.COMPLETE, Map.of("old:src/User.java", QUOTE, "new:src/User.java", "class User {}"));
        var old = new EvidenceCitation(
                DIFF, "inputs/sources/scm/diff.patch", "src/User.java", CitationSide.OLD, "base", 3, 3, QUOTE, null);
        assertThat(probe.presence(old)).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    void shouldNotSayForAQuoteOfARecordOfTheChange() {
        var probe = probe(SourceCompleteness.COMPLETE, Map.of("new:src/User.java", "class User {}"));
        var record = new EvidenceCitation(
                DIFF, "context/change.json", "context/change.json#/diff", CitationSide.NEW, null, 1, 1, QUOTE, null);
        assertThat(probe.presence(record)).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    void shouldFindTheQuoteStillPresentAtItsPathEvenWhenItsLineWasReindented() {
        var probe = probe(
                SourceCompleteness.COMPLETE, Map.of("new:src/User.java", "class User {\n        " + QUOTE + "\n}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.PRESENT);
    }

    @Test
    void shouldCallTheQuoteGoneWhenTheChangeRemovedItFromItsPath() {
        var probe =
                probe(SourceCompleteness.COMPLETE, Map.of("new:src/User.java", "class User {\n    requireUser();\n}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.GONE);
    }

    /** A renamed file keeps its code at the new path, so the problem's cause is still there. */
    @Test
    void shouldFindTheQuoteStillPresentWhenItsFileWasRenamed() {
        var probe = probe(
                SourceCompleteness.COMPLETE,
                Map.of("old:src/User.java", QUOTE, "new:src/Account.java", "class Account {\n    " + QUOTE + "\n}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.PRESENT);
    }

    /** A deleted file keeps only its old text in the change: its path is still captured, and its code is gone. */
    @Test
    void shouldCallTheQuoteGoneWhenItsFileWasDeleted() {
        var probe = probe(
                SourceCompleteness.COMPLETE,
                Map.of("old:src/User.java", QUOTE, "new:src/Other.java", "class Other {}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.GONE);
    }

    /** A file that the change no longer touches is not in the capture, and its text may still hold the quote. */
    @Test
    void shouldNotSayWhenTheCaptureNoLongerHoldsThePath() {
        var probe = probe(SourceCompleteness.COMPLETE, Map.of("new:src/Other.java", "class Other {}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    void shouldNotSayWhenTheSourceWasCapturedOnlyInPart() {
        var probe = probe(SourceCompleteness.PARTIAL, Map.of("new:src/User.java", "class User {}"));
        assertThat(probe.presence(cited("src/User.java"))).isEqualTo(Presence.UNKNOWN);
    }
}
