package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class SecretDiffScanner {
    private static final Logger log = LoggerFactory.getLogger(SecretDiffScanner.class);

    record SecretHit(String path, int newLine, String lineHash, String ruleId) {}

    private static final Pattern LOW_SIGNAL_PATH =
            Pattern.compile("(?i)(?:^|/)(?:tests?|spec|specs|fixtures?|__tests__|__mocks__|docs?|examples|samples)/"
                    + "|^(?:example|sample)/|\\.(?:example|sample|md)$|(?:^|/)\\.env\\.example$");
    private final JobEvidenceFiles evidence;
    private final NativeGitExecutor git;
    private final JsonMapper mapper;

    SecretDiffScanner(JobEvidenceFiles evidence, NativeGitExecutor git, JsonMapper mapper) {
        this.evidence = evidence;
        this.git = git;
        this.mapper = mapper;
    }

    List<SecretHit> scan(AgentJob job) {
        JsonNode snapshot = job.getEvidenceSnapshot();
        if (snapshot == null) throw new JobDeliveryException("Secret scan has no captured repository");
        String range = "";
        String headHash = "";
        String refsHash = "";
        for (JsonNode source : snapshot.path("manifest").path("sources")) {
            if (!"AVAILABLE".equals(source.path("state").path("availability").asString())) continue;
            if ("scm.pull-request.diff".equals(source.path("kind").asString())) {
                range = source.path("state")
                        .path("facts")
                        .path("immutableIdentity")
                        .asString();
            }
            if ("scm.repository.tree".equals(source.path("kind").asString())) {
                for (JsonNode artifact : source.path("artifacts")) {
                    if ((SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD")
                            .equals(artifact.path("path").asString())) {
                        headHash = artifact.path("sha256").asString();
                    } else if ((SandboxLayout.REPO_MOUNT_RELATIVE + ".git/hephaestus-captured-refs")
                            .equals(artifact.path("path").asString())) {
                        refsHash = artifact.path("sha256").asString();
                    }
                }
            }
        }
        String[] revisions = range.split(":", -1);
        if (revisions.length != 2
                || !range.matches(CitationVerification.GIT_OBJECT_ID + ":" + CitationVerification.GIT_OBJECT_ID)
                || !headHash.matches(CitationVerification.SHA256_HEX)
                || !refsHash.matches(CitationVerification.SHA256_HEX)) {
            throw new JobDeliveryException("Secret scan requires the captured diff range and repository identity");
        }
        Path repository = evidence.repositoryForVerification(job, headHash, refsHash);
        Path report = null;
        try {
            report = Files.createTempFile("hephaestus-secret-verdicts-", ".json");
            try (var output = Files.newOutputStream(report)) {
                git.executeInSnapshot(
                        repository,
                        new NativeGitExecutor.Request(
                                NativeGitExecutor.Operation.SCAN_SECRETS, List.of(revisions), null, null),
                        Duration.ofMinutes(5),
                        output);
            }
            if (Files.size(report) > SandboxOutputArchive.MAX_SINGLE_FILE_BYTES) {
                throw new JobDeliveryException("Secret scan verdicts exceed the result resource budget");
            }
            JsonNode result;
            try (var input = Files.newInputStream(report)) {
                result = mapper.readTree(input);
            }
            JsonNode rows = result == null ? null : result.path("verdicts");
            JsonNode skipped = result == null ? null : result.path("skipped");
            if (rows == null || !rows.isArray() || skipped == null || !skipped.isArray())
                throw new JobDeliveryException("Invalid secret scan verdicts");
            if (!skipped.isEmpty()) {
                // An unscanned file is an omission the review cannot see; the paths carry no secret.
                log.warn(
                        "Secret scan skipped {} oversized file(s): jobId={}, paths={}",
                        skipped.size(),
                        job.getId(),
                        skipped);
            }
            List<SecretHit> hits = new ArrayList<>();
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
                    throw new JobDeliveryException("Invalid secret scan verdict");
                }
                hits.add(new SecretHit(
                        row.path("path").asString(),
                        row.path("line").asInt(),
                        row.path("lineHash").asString(),
                        row.path("ruleId").asString()));
            }
            return List.copyOf(hits);
        } catch (IOException | RuntimeException exception) {
            throw new JobDeliveryException("Secret scan failed; review observations were not admitted", exception);
        } finally {
            if (report != null) FileUtils.deleteQuietly(report.toFile());
        }
    }

    boolean isLowSignalPath(String path) {
        return LOW_SIGNAL_PATH.matcher(path).find();
    }
}
