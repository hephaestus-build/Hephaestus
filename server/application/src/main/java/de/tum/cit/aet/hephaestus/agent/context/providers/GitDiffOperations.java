package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class GitDiffOperations {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    /** What one diff capture stages, and what {@code scm.pull-request.diff} owns among the staged files. */
    public static final Set<String> FILES = Set.of("diff.patch", "diff_stat.txt", "diff_summary.md", "diff_paths.nul");

    private final NativeGitExecutor git;

    public GitDiffOperations(NativeGitExecutor git) {
        this.git = git;
    }

    public String @Nullable [] resolveDiffRange(RepositoryKey repository, String baseSha, String headSha) {
        var output = new ByteArrayOutputStream();
        git.execute(
                repository,
                new Request(Operation.RESOLVE_DIFF_RANGE, List.of(baseSha, headSha), null, null),
                TIMEOUT,
                output);
        String range = output.toString(StandardCharsets.UTF_8).strip();
        if (range.isEmpty()) return null;
        String[] commits = range.split("\\n");
        if (commits.length != 2
                || !commits[0].matches(CitationVerification.GIT_OBJECT_ID)
                || !commits[1].equals(headSha)) {
            throw new JobPreparationException("Native Git returned an invalid diff range");
        }
        return commits;
    }

    public DiffCapture capture(RepositoryKey repository, String base, String head) {
        Path directory;
        try {
            directory = Files.createTempDirectory("review-diff-");
        } catch (IOException exception) {
            throw new JobPreparationException("Could not allocate diff staging", exception);
        }
        try {
            Path archive = directory.resolve("transfer.tar");
            try (var output = Files.newOutputStream(archive)) {
                git.execute(
                        repository,
                        new Request(Operation.REVIEW_DIFF, List.of(base, head), null, null),
                        TIMEOUT,
                        output);
            }
            Map<String, Path> files = new LinkedHashMap<>();
            try (var input = new TarArchiveInputStream(Files.newInputStream(archive))) {
                for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                    String name = entry.getName();
                    if (!FILES.contains(name)
                            || files.containsKey(name)
                            || !entry.isFile()
                            || entry.isLink()
                            || entry.isSymbolicLink()) {
                        throw new IOException("Unexpected diff archive entry");
                    }
                    Path target = directory.resolve(name);
                    Files.copy(input, target);
                    files.put(name, target);
                }
            }
            if (!files.keySet().equals(FILES)) throw new IOException("Incomplete diff archive");
            Files.delete(archive);
            return new DiffCapture(directory, Map.copyOf(files));
        } catch (IOException | RuntimeException exception) {
            try {
                FileUtils.deleteDirectory(directory.toFile());
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw new JobPreparationException("Could not prepare repository diff", exception);
        }
    }

    public CommitCapture captureCommits(RepositoryKey repository, String base, String head) {
        Path path;
        try {
            path = Files.createTempFile("review-commits-", ".json");
        } catch (IOException exception) {
            throw new JobPreparationException("Could not allocate commit staging", exception);
        }
        try (var output = Files.newOutputStream(path)) {
            git.execute(
                    repository,
                    new Request(Operation.REVIEW_COMMITS, List.of(base, head), null, null),
                    TIMEOUT,
                    output);
            return new CommitCapture(path);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw new JobPreparationException("Could not capture review commits", exception);
        }
    }

    public record CommitCapture(Path path) implements Closeable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    public record DiffCapture(Path directory, Map<String, Path> files) implements Closeable {
        public boolean isEmpty() throws IOException {
            return Files.size(directory.resolve("diff.patch")) == 0;
        }

        @Override
        public void close() throws IOException {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
