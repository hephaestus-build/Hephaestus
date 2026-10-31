package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCitableTexts;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

class JobEvidenceReadReceiptsTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private static final SourceKind SOURCE = new SourceKind("scm.repository.tree");
    private static final String WITNESS = "repository/HEAD";
    private static final byte[] BYTES = "Captured text\n".getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = ProvenanceDigest.sha256Hex(BYTES);
    private final JsonMapper mapper = new JsonMapper();
    private final ClasspathSourceCatalogRegistry catalogs =
            new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());

    @TempDir
    Path root;

    private JobEvidenceFiles files() {
        AutoCloseable released = () -> {};
        var copies = mock(
                EvidenceFolderPersonDataCatalog.class,
                invocation -> invocation.getMethod().getName().equals("finishCapture")
                        ? released
                        : Answers.RETURNS_DEFAULTS.answer(invocation));
        return new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(ReviewJobRepository.class), Clock.systemUTC(), copies);
    }

    private ReviewJob job() {
        var job = RuntimeReviewTestData.job();
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        return job;
    }

    private JobFolderIndex manifest(List<CitableText> texts, List<FolderSource> witnesses) {
        var empty = new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())
                .build(new ItemKind("scm.pull_request"), Map.of(SOURCE, CaptureContribution.empty()));
        var index = Objects.requireNonNull(empty.manifest());
        return new JobFolderIndex(
                index.contractVersion(),
                index.catalogDigest(),
                index.itemKind(),
                index.capturedAt(),
                index.sources(),
                List.of(),
                witnesses,
                texts);
    }

    private List<FolderSource> witness() {
        return List.of(new FolderSource(SOURCE, new CapturedSource(WITNESS, "text/plain", DIGEST, BYTES.length)));
    }

    private ReadReceipt receipt(String path, @Nullable String digest) {
        return new ReadReceipt(
                SOURCE, WITNESS, path, "commit-one", ReadReceiptStatus.COMPLETE, digest, null, null, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void shouldIgnoreReadsWithoutAValidContentDigest(@Nullable String digest) {
        var index = manifest(List.of(), List.of());
        assertThat(files().registerReadReceipts(job(), index, List.of(receipt("file.txt", digest)), catalogs))
                .isEqualTo(index);
    }

    @Test
    void shouldAuthenticateAndDeduplicateAReadOfTheSourceFileItself() {
        var files = files();
        var job = job();
        var prepared = files.prepare(job, new PreparedEvidence(Map.of(WITNESS, BYTES), null), null);
        try {
            var index = manifest(List.of(), witness());
            var updated = files.registerReadReceipts(
                    job, index, List.of(receipt(WITNESS, DIGEST), receipt(WITNESS, DIGEST)), catalogs);
            assertThat(updated.citableTexts())
                    .containsExactly(
                            new CitableText(SOURCE.value(), WITNESS, CitationSide.NEW, "commit-one", WITNESS, DIGEST));
            assertThat(updated.files()).isEqualTo(index.files());
            assertThat(updated.sources()).isEqualTo(index.sources());
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldLocateAHostReadOnlyInItsUniqueCapturedDirectory() {
        var files = files();
        var job = job();
        job.setCapture(mapper.createObjectNode().set("directories", mapper.valueToTree(List.of("repository/"))));
        var prepared = files.prepare(
                job, new PreparedEvidence(Map.of(WITNESS, BYTES, "repository/file.txt", BYTES), null), null);
        try {
            var updated = files.registerReadReceipts(
                    job, manifest(List.of(), witness()), List.of(receipt("file.txt", DIGEST)), catalogs);
            assertThat(updated.citableTexts())
                    .containsExactly(new CitableText(
                            SOURCE.value(), "file.txt", CitationSide.NEW, "commit-one", "repository/file.txt", DIGEST));
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldAuthenticateAScanReadWithItsItemRecordOutsideTheCapturedTree() throws Exception {
        String commit = "a".repeat(40);
        String recordPath = "context/item.json";
        byte[] record = mapper.writeValueAsBytes(Map.of(
                "repositoryId", "1",
                "providerId", "1",
                "path", "file.txt",
                "blobId", "b".repeat(40),
                "commitId", commit));
        Path tree = Files.createDirectories(root.resolve("scan"));
        Files.write(tree.resolve("file.txt"), BYTES);
        var texts = ScmCitableTexts.tree(tree, commit);
        var index = manifest(
                texts,
                List.of(new FolderSource(
                        SOURCE,
                        new CapturedSource(
                                recordPath, "application/json", ProvenanceDigest.sha256Hex(record), record.length))));
        var files = files();
        var job = job();
        try (var prepared = files.prepare(
                job, new PreparedEvidence(Map.of(recordPath, record, "repos/reviewed/file.txt", BYTES), index), null)) {
            assertThat(prepared.filesOnDisk()).doesNotContainKey("repos/reviewed/.git/HEAD");
            var read = new ReadReceipt(
                    SOURCE, recordPath, "file.txt", commit, ReadReceiptStatus.COMPLETE, DIGEST, 1, 2, null);
            assertThat(files.registerReadReceipts(job, index, List.of(read), catalogs)
                            .citableTexts())
                    .containsExactlyElementsOf(texts);
        }
    }

    @Test
    void shouldKeepOtherSourcePathsSidesAndVersionsSeparateFromTheHostRead() {
        var files = files();
        var job = job();
        var prior = List.of(
                new CitableText("scm.pull-request.core", WITNESS, CitationSide.NEW, "commit-one", WITNESS, DIGEST),
                new CitableText(SOURCE.value(), "other.txt", CitationSide.NEW, "commit-one", WITNESS, DIGEST),
                new CitableText(SOURCE.value(), WITNESS, CitationSide.OLD, "commit-one", WITNESS, DIGEST),
                new CitableText(SOURCE.value(), WITNESS, CitationSide.NEW, "commit-two", WITNESS, DIGEST));
        var prepared = files.prepare(job, new PreparedEvidence(Map.of(WITNESS, BYTES), null), null);
        try {
            var updated = files.registerReadReceipts(
                    job, manifest(prior, witness()), List.of(receipt(WITNESS, DIGEST)), catalogs);
            var expected = new ArrayList<>(prior);
            expected.add(new CitableText(SOURCE.value(), WITNESS, CitationSide.NEW, "commit-one", WITNESS, DIGEST));
            assertThat(updated.citableTexts()).containsExactlyElementsOf(expected);
        } finally {
            prepared.close();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "absent-witness",
                "wrong-source",
                "missing-witness-file",
                "changed-witness",
                "missing-content",
                "no-capture",
                "no-directory",
                "unrelated-directory",
                "ambiguous-directory",
                "ambiguous-text",
                "changed-text"
            })
    void shouldRefuseAReadWhoseWitnessOrCapturedTextCannotBeAuthenticated(String defect) {
        var files = files();
        var job = job();
        var roots = switch (defect) {
            case "ambiguous-directory" -> List.of("repository/", "repository/nested/");
            case "unrelated-directory" -> List.of("other/", "repository");
            default -> List.<String>of();
        };
        String sourcePath = defect.equals("ambiguous-directory") ? "repository/nested/HEAD" : WITNESS;
        if (!defect.equals("no-capture"))
            job.setCapture(mapper.createObjectNode().set("directories", mapper.valueToTree(roots)));
        var witnesses = defect.equals("absent-witness")
                ? List.<FolderSource>of()
                : List.of(new FolderSource(
                        defect.equals("wrong-source") ? new SourceKind("scm.pull-request.core") : SOURCE,
                        new CapturedSource(
                                sourcePath,
                                "text/plain",
                                defect.equals("changed-witness") ? "a".repeat(64) : DIGEST,
                                BYTES.length)));
        var text = new CitableText(
                SOURCE.value(),
                "file.txt",
                CitationSide.NEW,
                "commit-one",
                "repository/file.txt",
                defect.equals("changed-text") ? "a".repeat(64) : DIGEST);
        var texts = defect.equals("ambiguous-text")
                ? List.of(text, text)
                : defect.equals("changed-text") || defect.equals("missing-content")
                        ? List.of(text)
                        : List.<CitableText>of();
        var content = new LinkedHashMap<String, byte[]>();
        if (!defect.equals("missing-witness-file")) content.put(sourcePath, BYTES);
        if (!defect.equals("missing-content")) content.put("repository/file.txt", BYTES);
        var prepared = files.prepare(job, new PreparedEvidence(content, null), null);
        try {
            var read = new ReadReceipt(
                    SOURCE, sourcePath, "file.txt", "commit-one", ReadReceiptStatus.COMPLETE, DIGEST, null, null, null);
            var message = switch (defect) {
                case "absent-witness", "wrong-source", "missing-witness-file" ->
                    "The host read has no authenticated source witness";
                case "changed-witness" -> "Captured evidence digest mismatch";
                case "missing-content" -> "The host read content is unavailable";
                case "ambiguous-text" -> "Ambiguous host read text";
                case "changed-text" -> "The host read digest differs from its captured text";
                default -> "The host read has no unique captured directory";
            };
            assertThatThrownBy(
                            () -> files.registerReadReceipts(job, manifest(texts, witnesses), List.of(read), catalogs))
                    .isInstanceOf(
                            defect.equals("changed-witness")
                                    ? IllegalStateException.class
                                    : IllegalArgumentException.class)
                    .hasMessage(message);
        } finally {
            prepared.close();
        }
    }

    @Test
    void shouldReadOnlyBytesWithTheCapturedDigest() throws Exception {
        var files = files();
        var job = job();
        var prepared = files.prepare(job, new PreparedEvidence(Map.of(WITNESS, BYTES), null), null);
        try {
            assertThat(files.readText(job, WITNESS, DIGEST)).contains("Captured text\n");
            assertThat(files.readText(job, "missing.txt", DIGEST)).isEmpty();
            assertThatThrownBy(() -> files.readText(job, WITNESS, "a".repeat(64)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            prepared.close();
        }
    }
}
