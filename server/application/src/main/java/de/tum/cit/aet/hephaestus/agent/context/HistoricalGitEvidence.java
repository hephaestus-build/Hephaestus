package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

@Component
public class HistoricalGitEvidence {
    private final JobEvidenceFiles files;
    private final NativeGitExecutor git;

    public HistoricalGitEvidence(JobEvidenceFiles files, NativeGitExecutor git) {
        this.files = files;
        this.git = git;
    }

    public record Citation(String revision, String path, String quote, int startLine, int endLine) {}

    private record Blob(String revision, String path) {}

    /**
     * Every submitted citation answered from one native transfer. The archive names each blob by the
     * index of its (revision, path) pair in the request and carries no entry for a pair that does not
     * exist at its revision, so such a citation comes back as {@link JobEvidenceFiles.QuoteMatch#absent()}
     * rather than costing the whole batch.
     */
    public Map<Citation, JobEvidenceFiles.QuoteMatch> verifyAll(
            AgentJob job, String headDigest, String refsDigest, String pinnedHead, List<Citation> submitted) {
        if (submitted.isEmpty()) return Map.of();
        List<Citation> citations = submitted.stream().distinct().toList();
        List<Blob> blobs = citations.stream()
                .map(citation -> new Blob(citation.revision(), citation.path()))
                .distinct()
                .toList();
        Path repository = files.repositoryForVerification(job, headDigest, refsDigest);
        List<String> arguments = new ArrayList<>();
        arguments.add(pinnedHead);
        blobs.forEach(blob -> {
            arguments.add(blob.revision());
            arguments.add(blob.path());
        });
        Path directory = null;
        try {
            directory = Files.createTempDirectory("hephaestus-cited-blobs-");
            Path archive = directory.resolve("blobs.tar");
            try (var output = Files.newOutputStream(archive)) {
                git.executeInSnapshot(
                        repository,
                        new NativeGitExecutor.Request(NativeGitExecutor.Operation.CITED_BLOBS, arguments, null, null),
                        Duration.ofMinutes(5),
                        output);
            }
            Map<Citation, JobEvidenceFiles.QuoteMatch> verified = new LinkedHashMap<>();
            var received = new HashSet<Integer>();
            try (var input = new TarArchiveInputStream(Files.newInputStream(archive))) {
                for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                    if (!entry.isFile()
                            || entry.isLink()
                            || entry.isSymbolicLink()
                            || !entry.getName().matches("0|[1-9][0-9]*")) {
                        throw new JobDeliveryException("Invalid native citation archive entry");
                    }
                    int index;
                    try {
                        index = Integer.parseInt(entry.getName());
                    } catch (NumberFormatException exception) {
                        throw new JobDeliveryException("Invalid native citation archive index", exception);
                    }
                    if (index >= blobs.size() || !received.add(index)) {
                        throw new JobDeliveryException("Unexpected or duplicate native citation blob");
                    }
                    Blob identity = blobs.get(index);
                    Path blob = directory.resolve("blob");
                    Files.copy(input, blob);
                    for (Citation citation : citations) {
                        if (identity.revision().equals(citation.revision())
                                && identity.path().equals(citation.path())) {
                            verified.put(
                                    citation,
                                    JobEvidenceFiles.verifyUtf8AtLines(
                                            blob, citation.quote(), citation.startLine(), citation.endLine()));
                        }
                    }
                    Files.delete(blob);
                }
            }
            for (Citation citation : citations) {
                verified.putIfAbsent(citation, JobEvidenceFiles.QuoteMatch.absent());
            }
            return Map.copyOf(verified);
        } catch (IOException exception) {
            // The verifier, not the submission, failed: a 5xx the runner repeats, never a refusal.
            throw new IllegalStateException("Repository citations could not be verified", exception);
        } finally {
            if (directory != null) FileUtils.deleteQuietly(directory.toFile());
        }
    }
}
