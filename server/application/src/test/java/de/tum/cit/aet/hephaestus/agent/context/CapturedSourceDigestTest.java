package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CapturedSourceDigestTest extends BaseUnitTest {
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");
    private static final String RECORD = "{\"basis\":{\"captured_at\":\"%s\"},\"title\":\"%s\"}";
    private static final String THREAD =
            "{\"body\":\"%s\",\"synced_at\":\"%s\",\"created_at\":\"2026-01-01T00:00:00Z\"}\n";

    private final JsonMapper mapper = new JsonMapper();

    private String digest(String record, String threads, Instant capturedAt, SourceKind... kinds) {
        return CapturedSourceDigest.of(
                mapper,
                evidence(
                        capturedAt,
                        capture(CORE, "core/record.json", record, capturedAt),
                        capture(THREADS, "threads/threads.jsonl", threads, capturedAt),
                        Map.of("core/record.json", record, "threads/threads.jsonl", threads)),
                List.of(kinds));
    }

    private static SourceCapture capture(SourceKind kind, String path, String content, Instant capturedAt) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SourceCapture(
                kind,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(capturedAt, null, capturedAt, null)),
                EvidenceSnapshotFixtures.citation(kind),
                List.of(new CapturedSource(path, "application/json", ProvenanceDigest.sha256Hex(bytes), bytes.length)));
    }

    private static PreparedEvidence evidence(
            Instant capturedAt, SourceCapture core, SourceCapture threads, Map<String, String> files) {
        var held = new LinkedHashMap<String, byte[]>();
        files.forEach((path, content) -> held.put(path, content.getBytes(StandardCharsets.UTF_8)));
        return new PreparedEvidence(
                held,
                new JobFolderIndex(
                        new SourceContractVersion("3.0.0"),
                        "a".repeat(64),
                        "scm.pull_request",
                        capturedAt,
                        List.of(core, threads),
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private static final Instant FIRST = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void shouldStayEqualWhenOnlyTheCaptureAndSyncTimesMove() {
        assertThat(digest(
                        RECORD.formatted(LATER, "Title"),
                        THREAD.formatted("Please rename", LATER),
                        LATER,
                        CORE,
                        THREADS))
                .isEqualTo(digest(
                        RECORD.formatted(FIRST, "Title"),
                        THREAD.formatted("Please rename", FIRST),
                        FIRST,
                        CORE,
                        THREADS));
    }

    @Test
    void shouldChangeWhenAReadSourceChanges() {
        String before =
                digest(RECORD.formatted(FIRST, "Title"), THREAD.formatted("Please rename", FIRST), FIRST, THREADS);

        assertThat(digest(RECORD.formatted(FIRST, "Title"), THREAD.formatted("Please remove", FIRST), FIRST, THREADS))
                .isNotEqualTo(before);
    }

    @Test
    void shouldNameACheckoutByItsCommitNotByItsListOfRefs() {
        var tree = new SourceKind("scm.repository.tree");
        assertThat(checkoutDigest("commit:tree", "refs after a push elsewhere", tree))
                .isEqualTo(checkoutDigest("commit:tree", "refs before", tree))
                .isNotEqualTo(checkoutDigest("other:tree", "refs before", tree));
    }

    private String checkoutDigest(String identity, String refs, SourceKind tree) {
        byte[] bytes = refs.getBytes(StandardCharsets.UTF_8);
        var capture = new SourceCapture(
                tree,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(FIRST, null, null, identity)),
                EvidenceSnapshotFixtures.citation(tree),
                List.of(new CapturedSource(
                        "repos/reviewed/.git/hephaestus-captured-refs",
                        "text/plain",
                        ProvenanceDigest.sha256Hex(bytes),
                        bytes.length)));
        return CapturedSourceDigest.of(
                mapper,
                new PreparedEvidence(
                        Map.of(),
                        new JobFolderIndex(
                                new SourceContractVersion("3.0.0"),
                                "a".repeat(64),
                                "scm.pull_request",
                                FIRST,
                                List.of(capture),
                                List.of(),
                                List.of(),
                                List.of())),
                List.of(tree));
    }

    @Test
    void shouldIgnoreASourceThatThePracticeDoesNotRead() {
        String before = digest(RECORD.formatted(FIRST, "Title"), THREAD.formatted("Please rename", FIRST), FIRST, CORE);

        assertThat(digest(RECORD.formatted(FIRST, "Title"), THREAD.formatted("Please remove", FIRST), FIRST, CORE))
                .isEqualTo(before);
    }

    @Test
    void shouldChangeWithWhichSourcesThePracticeReads() {
        String record = RECORD.formatted(FIRST, "Title");
        String threads = THREAD.formatted("Please rename", FIRST);

        assertThat(digest(record, threads, FIRST, CORE)).isNotEqualTo(digest(record, threads, FIRST, CORE, THREADS));
    }
}
