package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.requireDigest;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.safePath;
import static de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles.CHECKOUTS;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationRequirement;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reads of a review attempt's captured files that admission makes: the folder it verifies, a captured text by its
 * recorded digest, the host reads that a result adds to the manifest, and the original inputs that a composition
 * retains. Each read requires the folder of the job's running attempt that {@link JobEvidenceFiles} keeps.
 */
@Component
public class EvidenceFileReads {
    private static final Logger log = LoggerFactory.getLogger(EvidenceFileReads.class);

    /** Invalid UTF-8 cannot match a quote: this lone surrogate is rejected by quoteDigest. */
    private static final String UNDECODABLE = "\uDC00";

    private final JobEvidenceFiles folders;
    private final FabricLayout layout;
    private final ReviewJobRepository jobs;

    public EvidenceFileReads(JobEvidenceFiles folders, FabricLayout layout, ReviewJobRepository jobs) {
        this.folders = folders;
        this.layout = layout;
        this.jobs = jobs;
    }

    @FunctionalInterface
    public interface TextInspection<T> {
        T inspect(Reader reader) throws IOException;
    }

    public EvidenceDirectory verificationDirectory(ReviewJob job) {
        folders.requireAttemptFolder(job);
        return new EvidenceDirectory("inputs/", directory(layout.jobsRoot(), job));
    }

    /**
     * Adds host reads only when both their source witness and staged content digest still match. A search's digest
     * names the checkout it searched, not a file, so admission checks it against {@link #capturedCheckoutDigest}.
     */
    public JobFolderIndex registerReadReceipts(
            ReviewJob job,
            JobFolderIndex manifest,
            List<ReadReceipt> receipts,
            ClasspathSourceCatalogRegistry catalogs) {
        var texts = new ArrayList<>(manifest.citableTexts());
        for (var receipt : receipts) {
            String digest = receipt.contentDigest();
            if (receipt.status() == ReadReceiptStatus.SEARCHED || digest == null || !digest.matches("[0-9a-f]{64}"))
                continue;
            var witness = manifest.files().stream()
                    .filter(file -> file.kind().equals(receipt.sourceKind())
                            && file.file().path().equals(receipt.sourcePath()))
                    .toList();
            if (witness.size() != 1
                    || !existsWithDigest(
                            job, receipt.sourcePath(), witness.getFirst().file().sha256())) {
                throw new IllegalArgumentException("The host read has no authenticated source witness");
            }
            var contract = catalogs.requireSource(receipt.sourceKind());
            String commit = contract.citation().commit() == CitationRequirement.FORBIDDEN ? null : receipt.version();
            var existing = texts.stream()
                    .filter(text -> text.source().equals(receipt.sourceKind().value())
                            && text.path().equals(receipt.path())
                            && text.side() == CitationSide.NEW
                            && Objects.equals(text.commit(), commit))
                    .toList();
            String stagedPath;
            if (existing.size() == 1) stagedPath = existing.getFirst().stagedPath();
            else if (existing.size() > 1) throw new IllegalArgumentException("Ambiguous host read text");
            else if (receipt.path().equals(receipt.sourcePath())) stagedPath = receipt.path();
            else
                stagedPath = capturedDirectory(job, receipt.sourcePath())
                                .orElseThrow(() ->
                                        new IllegalArgumentException("The host read has no unique captured directory"))
                        + receipt.path();
            if (!existsWithDigest(job, stagedPath, digest))
                throw new IllegalArgumentException("The host read content is unavailable");
            if (existing.isEmpty())
                texts.add(new CitableText(
                        receipt.sourceKind().value(), receipt.path(), CitationSide.NEW, commit, stagedPath, digest));
            else if (!existing.getFirst().sha256().equals(digest))
                throw new IllegalArgumentException("The host read digest differs from its captured text");
        }
        return new JobFolderIndex(
                manifest.contractVersion(),
                manifest.catalogDigest(),
                manifest.itemKind(),
                manifest.capturedAt(),
                manifest.sources(),
                manifest.refusals(),
                manifest.files(),
                texts);
    }

    /** The one captured directory that holds a source witness, such as {@code repos/reviewed/} for its Git HEAD. */
    private static Optional<String> capturedDirectory(ReviewJob job, String sourcePath) {
        JsonNode capture = job.getCapture();
        var roots = new ArrayList<String>();
        if (capture != null)
            for (JsonNode directory : capture.path("directories")) {
                String root = directory.asString();
                if (root.endsWith("/") && sourcePath.startsWith(root)) roots.add(root);
            }
        return roots.size() == 1 ? Optional.of(roots.getFirst()) : Optional.empty();
    }

    /**
     * The checkout digest that the capture recorded for the checkout a search receipt of the whole checkout searched
     * ({@link JobEvidenceFiles#checkoutDigests}). Empty for any other receipt, and for a checkout past the bound of a
     * search proof.
     */
    public Optional<String> capturedCheckoutDigest(ReviewJob job, ReadReceipt search) {
        JsonNode capture = job.getCapture();
        if (capture == null || search.status() != ReadReceiptStatus.SEARCHED || !".".equals(search.path()))
            return Optional.empty();
        return capturedDirectory(job, search.sourcePath())
                .map(root -> capture.path(CHECKOUTS).path(root).asString(null));
    }

    public Optional<String> readText(ReviewJob job, String path, String sha) {
        try {
            Path file = sourceFile(job, path);
            if (Files.size(file) > 32 * 1024 * 1024L)
                throw new IllegalArgumentException("Captured text exceeds its read bound");
            byte[] bytes = Files.readAllBytes(file);
            requireDigest(sha, ProvenanceDigest.sha256Hex(bytes));
            return Optional.of(decoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public <T> Optional<T> inspect(ReviewJob job, String filePath, String sha, TextInspection<T> inspection) {
        try {
            MessageDigest digest = ProvenanceDigest.sha256();
            T result;
            try (var reader = new InputStreamReader(
                    new DigestInputStream(Files.newInputStream(sourceFile(job, filePath)), digest), decoder())) {
                result = inspection.inspect(reader);
                reader.transferTo(Writer.nullWriter());
            }
            requireDigest(sha, ProvenanceDigest.hex(digest));
            return Optional.of(result);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public Optional<Boolean> containsUtf8AtLines(
            ReviewJob job, String filePath, String sha, String text, int startLine, int endLine) {
        try {
            QuoteMatch match = verifyUtf8AtLines(sourceFile(job, filePath), text, startLine, endLine);
            requireDigest(sha, Objects.requireNonNull(match.fileSha256()));
            return Optional.of(match.matches());
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean existsWithDigest(ReviewJob job, String filePath, String sha) {
        return inspect(job, filePath, sha, reader -> Boolean.TRUE).isPresent();
    }

    /** @param fileSha256 digest of the raw bytes read, or null when the cited file does not exist */
    public record QuoteMatch(boolean matches, @Nullable String fileSha256) {
        public static QuoteMatch absent() {
            return new QuoteMatch(false, null);
        }
    }

    public static QuoteMatch verifyUtf8AtLines(Path file, String text, int startLine, int endLine) throws IOException {
        if (startLine < 1 || endLine < startLine || text.isEmpty())
            throw new IllegalArgumentException("Invalid citation quote or line range");
        MessageDigest digest = ProvenanceDigest.sha256();
        boolean found = false;
        try (var reader = new InputStreamReader(new DigestInputStream(Files.newInputStream(file), digest), decoder())) {
            char[] buffer = new char[8192];
            StringBuilder window = new StringBuilder();
            long line = 1;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (found) continue;
                for (int i = 0; i < read; i++) {
                    char character = buffer[i];
                    if (line >= startLine && line <= endLine) window.append(character);
                    if (character == '\n') line++;
                }
                found = window.indexOf(text) >= 0;
                window.delete(0, Math.max(0, window.length() - text.length() + 1));
            }
        }
        return new QuoteMatch(found, ProvenanceDigest.hex(digest));
    }

    private static CharsetDecoder decoder() {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(UNDECODABLE);
    }

    /** The manifest's rendered companion, which {@link JobFolderIndexBuilder} writes beside it. */
    private static final String INDEX_MARKDOWN_PATH = "INDEX.md";

    /**
     * The finite inputs the feedback of this attempt is composed from, exactly as its folder holds them: the manifest
     * and its rendered index (with every source's recorded bytes, digests, absences and omissions), every captured
     * context record, the practice index and whole standards, the staged history of earlier results, and the
     * checkout's recorded HEAD and refs. Sandbox work and the checkout's objects are not among them. Read while the
     * attempt still owns its folder; a context record the manifest does not list with these exact bytes, or any
     * unreadable file, leaves nothing to retain rather than a part.
     */
    public Optional<Map<String, byte[]>> originalCompositionInputs(ResultAdmissionService.AdmissionIdentity identity) {
        Path root = directory(
                layout.jobsRoot(), identity.workspaceId(), identity.jobId(), identity.attempt(), identity.runId());
        jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                .filter(job -> directory(layout.jobsRoot(), job).equals(root))
                .ifPresent(folders::requireAttemptFolder);
        try {
            var files = new TreeMap<String, byte[]>();
            byte[] index = Files.readAllBytes(regularFile(root, SandboxLayout.MANIFEST_PATH));
            files.put(SandboxLayout.MANIFEST_PATH, index);
            var recordedJob = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                    .orElseThrow();
            var capture = Objects.requireNonNull(recordedJob.getCapture());
            var manifest = new JsonMapper().treeToValue(capture.path("manifest"), JobFolderIndex.class);
            files.put(SandboxLayout.MANIFEST_PATH, new JsonMapper().writeValueAsBytes(manifest));
            // The rendered index beside the manifest, only as this attempt was staged with it.
            Path rendered = root.resolve(INDEX_MARKDOWN_PATH);
            if (Files.isRegularFile(rendered, LinkOption.NOFOLLOW_LINKS))
                files.put(INDEX_MARKDOWN_PATH, Files.readAllBytes(rendered));
            for (String prefix : List.of(
                    SandboxLayout.CONTEXT_PREFIX, SandboxLayout.PRACTICES_PREFIX, SandboxLayout.HISTORY_PREFIX)) {
                Path directory = root.resolve(prefix);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var entries = Files.walk(directory)) {
                    for (Path path : entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                        files.put(root.relativize(path).toString(), Files.readAllBytes(path));
                    }
                }
            }
            var listed = new HashMap<String, CapturedSource>();
            manifest.files().forEach(sourceFile -> listed.put(sourceFile.file().path(), sourceFile.file()));
            for (var text : manifest.citableTexts()) {
                byte[] bytes = Files.readAllBytes(regularFile(root, text.stagedPath()));
                requireDigest(text.sha256(), ProvenanceDigest.sha256Hex(bytes));
                files.put(text.stagedPath(), bytes);
            }
            for (var file : files.entrySet()) {
                boolean recorded = file.getKey().startsWith(SandboxLayout.CONTEXT_PREFIX)
                        || file.getKey().startsWith(SandboxLayout.REPO_MOUNT_RELATIVE);
                if (!recorded) continue;
                CapturedSource sourceFile = listed.get(file.getKey());
                if (sourceFile == null
                        && manifest.citableTexts().stream()
                                .anyMatch(text -> text.stagedPath().equals(file.getKey())
                                        && text.sha256().equals(ProvenanceDigest.sha256Hex(file.getValue())))) continue;
                if (sourceFile == null
                        || sourceFile.bytes() != file.getValue().length
                        || !sourceFile.sha256().equals(ProvenanceDigest.sha256Hex(file.getValue()))) {
                    return Optional.empty();
                }
            }
            for (String path : listed.keySet()) {
                if (path.startsWith(SandboxLayout.CONTEXT_PREFIX) && !files.containsKey(path)) return Optional.empty();
            }
            return Optional.of(Collections.unmodifiableMap(files));
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "Original writer inputs unreadable: jobId={}, type={}",
                    identity.jobId(),
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Path regularFile(Path root, String relative) throws IOException {
        Path file = safePath(root, relative);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a captured file");
        return file;
    }

    private Path sourceFile(ReviewJob job, String filePath) throws IOException {
        folders.requireAttemptFolder(job);
        Path root = directory(layout.jobsRoot(), job);
        Path sourceFile = safePath(root, filePath);
        if (!sourceFile.toRealPath().startsWith(root.toRealPath())
                || !Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Evidence path is not a regular captured file");
        }
        return sourceFile;
    }
}
