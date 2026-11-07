package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class JobEvidenceFilesCheckoutDigestTest extends BaseUnitTest {

    /**
     * The digest that {@code pi-verified-search.spec.ts} pins for the same checkout. The two sides must agree on it,
     * or admission refuses every search.
     */
    private static final String PINNED = "bda91db5849fd6d92795d99bb6646b48c8e92d78ee215d2e661222554b8e73f5";

    private static final SourceKind TREE = new SourceKind("scm.repository.tree");
    private static final String ROOT = "repos/reviewed/";

    private final JsonMapper mapper = new JsonMapper();

    @TempDir
    Path root;

    /** The pinned checkout of the spec, as the server stages it: a capture holds no link. */
    private Path checkout() throws IOException {
        Path checkout = Files.createDirectories(root.resolve("checkout"));
        write(checkout, "a/x.txt", "one\n");
        write(checkout, "a.b", "dot\n");
        write(checkout, "b.txt", "two\n");
        write(checkout, "nested/ü.txt", "umlaut\n");
        write(checkout, ".git/HEAD", "ref: refs/heads/main\n");
        write(checkout, "nested/.git/config", "[core]\n");
        Files.createDirectories(checkout.resolve("empty"));
        return checkout;
    }

    private static void write(Path checkout, String path, String text) throws IOException {
        Path file = checkout.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private ReviewJob job(Map<String, String> checkouts) {
        var job = RuntimeReviewTestData.job();
        var capture = mapper.createObjectNode();
        capture.set("directories", mapper.valueToTree(List.of(ROOT)));
        capture.set(JobEvidenceFiles.CHECKOUTS, mapper.valueToTree(checkouts));
        job.setCapture(capture);
        return job;
    }

    private JobEvidenceFiles files() {
        return new JobEvidenceFiles(
                new FabricLayout(root.toString()),
                mock(ReviewJobRepository.class),
                Clock.systemUTC(),
                mock(EvidenceFolderPersonDataCatalog.class));
    }

    private static ReadReceipt search(String path) {
        return new ReadReceipt(
                TREE, ROOT + ".git/HEAD", path, "commit", ReadReceiptStatus.SEARCHED, PINNED, null, null, "absent", 0);
    }

    @Test
    void shouldRecordTheDigestThatTheLoopNamesForTheSameCheckout() throws Exception {
        assertThat(JobEvidenceFiles.checkoutDigests(List.of(new EvidenceDirectory(ROOT, checkout()))))
                .containsExactly(Map.entry(ROOT, PINNED));
    }

    @Test
    void shouldRecordAnotherDigestWhenAFileDiffers() throws Exception {
        Path checkout = checkout();
        write(checkout, "b.txt", "TWO\n");
        assertThat(JobEvidenceFiles.checkoutDigest(checkout, Long.MAX_VALUE))
                .isPresent()
                .get()
                .isNotEqualTo(PINNED);
    }

    @Test
    void shouldRecordNoDigestForACheckoutPastTheBoundOfASearchProof() throws Exception {
        // The checkout holds 4 + 4 + 4 + 7 bytes outside .git; the proof stops as the total passes its bound.
        assertThat(JobEvidenceFiles.checkoutDigest(checkout(), 18)).isEmpty();
        assertThat(JobEvidenceFiles.checkoutDigest(checkout(), 19)).contains(PINNED);
    }

    @Test
    void shouldReadTheRecordedDigestForASearchOfTheWholeCheckoutOnly() {
        var job = job(Map.of(ROOT, PINNED));
        var read =
                new ReadReceipt(TREE, ROOT + ".git/HEAD", "b.txt", "c", ReadReceiptStatus.COMPLETE, PINNED, 1, 1, null, null);
        assertThat(files().capturedCheckoutDigest(job, search("."))).contains(PINNED);
        assertThat(files().capturedCheckoutDigest(job, search("a"))).isEmpty();
        assertThat(files().capturedCheckoutDigest(job, read)).isEmpty();
        assertThat(files().capturedCheckoutDigest(job(Map.of()), search("."))).isEmpty();
    }

    @Test
    void shouldNotTakeASearchDigestForAFileRead() {
        var catalogs = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        var manifest = Objects.requireNonNull(new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())
                .build(new ItemKind("scm.pull_request"), Map.of(TREE, CaptureContribution.empty()))
                .manifest());
        assertThat(files().registerReadReceipts(job(Map.of(ROOT, PINNED)), manifest, List.of(search(".")), catalogs))
                .isEqualTo(manifest);
    }
}
