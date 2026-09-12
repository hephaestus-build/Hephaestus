package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the deterministic secret scan over a captured change while it is being prepared, so admission
 * reads the recorded verdicts instead of launching a container inside the request.
 */
final class SecretDiffScanner {
    private static final Logger log = LoggerFactory.getLogger(SecretDiffScanner.class);

    private static final Pattern LOW_SIGNAL_PATH =
            Pattern.compile("(?i)(?:^|/)(?:tests?|spec|specs|fixtures?|__tests__|__mocks__|docs?|examples|samples)/"
                    + "|^(?:example|sample)/|\\.(?:example|sample|md)$|(?:^|/)\\.env\\.example$");
    private final NativeGitExecutor git;
    private final JsonMapper mapper;

    SecretDiffScanner(NativeGitExecutor git, JsonMapper mapper) {
        this.git = git;
        this.mapper = mapper;
    }

    /** The verdicts over the change {@code evidence} captured, bound to the diff artifact it staged. */
    SecretScan scan(UUID jobId, PreparedEvidence evidence) {
        CapturedEvidence captured = CapturedEvidence.of(Objects.requireNonNull(evidence.manifest(), "manifest"));
        String range = captured.immutableIdentity(PracticeSubjectClause.DIFF_SOURCE);
        CapturedEvidence.Artifact diff = captured.artifact(CapturedEvidence.DIFF_ARTIFACT);
        Path repository = evidence.directories().stream()
                .filter(directory -> directory.target().equals(SandboxLayout.REPO_MOUNT_RELATIVE))
                .map(EvidenceDirectory::source)
                .findFirst()
                .orElse(null);
        if (range == null
                || !range.matches(CitationVerification.GIT_OBJECT_ID + ":" + CitationVerification.GIT_OBJECT_ID)
                || diff == null
                || !diff.kind().equals(PracticeSubjectClause.DIFF_SOURCE)
                || repository == null) {
            throw new JobPreparationException(
                    "Secret scan requires the captured diff range and repository snapshot: jobId=" + jobId);
        }
        Path report = null;
        try {
            report = Files.createTempFile("hephaestus-secret-verdicts-", ".json");
            try (var output = Files.newOutputStream(report)) {
                git.executeInSnapshot(
                        repository,
                        new NativeGitExecutor.Request(
                                NativeGitExecutor.Operation.SCAN_SECRETS, List.of(range.split(":", -1)), null, null),
                        Duration.ofMinutes(5),
                        output);
            }
            if (Files.size(report) > SandboxOutputArchive.MAX_SINGLE_FILE_BYTES) {
                throw new JobPreparationException("Secret scan verdicts exceed the result resource budget");
            }
            JsonNode result;
            try (var input = Files.newInputStream(report)) {
                result = mapper.readTree(input);
            }
            JsonNode rows = result == null ? null : result.path("verdicts");
            JsonNode skipped = result == null ? null : result.path("skipped");
            if (rows == null || !rows.isArray() || skipped == null || !skipped.isArray())
                throw new JobPreparationException("Invalid secret scan verdicts");
            if (!skipped.isEmpty()) {
                // An unscanned file is an omission the review cannot see; the paths carry no secret.
                log.warn(
                        "Secret scan skipped {} oversized file(s): jobId={}, paths={}", skipped.size(), jobId, skipped);
            }
            List<SecretScan.Hit> hits = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isObject()
                        || row.size() != 4
                        || !row.path("path").isString()
                        || row.path("path").asString().isBlank()
                        || !row.path("line").isIntegralNumber()
                        || row.path("line").asInt() < 1
                        || !row.path("ruleId").isString()
                        || row.path("ruleId").asString().isBlank()
                        || !row.path("lineHash").asString().matches(CitationVerification.SHA256_HEX)) {
                    throw new JobPreparationException("Invalid secret scan verdict");
                }
                hits.add(new SecretScan.Hit(
                        row.path("path").asString(),
                        row.path("line").asInt(),
                        row.path("lineHash").asString(),
                        row.path("ruleId").asString()));
            }
            return new SecretScan(CapturedEvidence.DIFF_ARTIFACT, diff.sha256(), hits);
        } catch (IOException | RuntimeException exception) {
            throw new JobPreparationException("Secret scan failed; the review cannot vouch for the change", exception);
        } finally {
            if (report != null) FileUtils.deleteQuietly(report.toFile());
        }
    }

    boolean isLowSignalPath(String path) {
        return LOW_SIGNAL_PATH.matcher(path).find();
    }
}
