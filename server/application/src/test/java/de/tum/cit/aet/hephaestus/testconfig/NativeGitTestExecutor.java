package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

/**
 * Real native Git/Node fixture; production always uses the isolated workload adapter. Every
 * operation runs the shipped helper except the two fetches: the helper accepts only an {@code https}
 * clone URL and disables the file transport, and a fixture only has a {@code file://} source. Those
 * two fetch by hand and keep the helper's semantics — the same fetch flags, and a
 * {@code FETCH_COMMIT} that fails unless the fetched ref carries the pinned commit.
 */
public final class NativeGitTestExecutor implements NativeGitExecutor {
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    public NativeGitTestExecutor(Path root) {
        this.root = root;
    }

    public Path mirrorPath(RepositoryKey key) {
        return root.resolve(key.workspaceId() + "-" + key.repositoryId()).resolve("mirror.git");
    }

    public void seedMirror(RepositoryKey key, Path source) {
        try {
            Files.createDirectories(mirrorPath(key).getParent());
            git(List.of("init", "--bare", "--template=", mirrorPath(key).toString()));
            git(List.of(
                    "--git-dir=" + mirrorPath(key),
                    "fetch",
                    source.toString(),
                    "+refs/heads/*:refs/remotes/origin/*",
                    "+refs/tags/*:refs/tags/*"));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void execute(RepositoryKey key, Request request, Duration timeout, OutputStream output) {
        executeAt(key, mirrorPath(key), request, timeout, output);
    }

    @Override
    public void executeInSnapshot(Path trustedRepository, Request request, Duration timeout, OutputStream output) {
        executeAt(new RepositoryKey(1, 1), trustedRepository.resolve(".git"), request, timeout, output);
    }

    private void executeAt(
            RepositoryKey key, Path gitDirectory, Request request, Duration timeout, OutputStream output) {
        try {
            Files.createDirectories(root);
            if (request.operation() == Operation.FETCH) {
                seedMirror(key, Path.of(URI.create(java.util.Objects.requireNonNull(request.cloneUrl()))));
                return;
            }
            if (request.operation() == Operation.FETCH_COMMIT) {
                git(List.of(
                        "--git-dir=" + mirrorPath(key),
                        "fetch",
                        "--force",
                        "--no-tags",
                        "--no-recurse-submodules",
                        Path.of(URI.create(java.util.Objects.requireNonNull(request.cloneUrl())))
                                .toString(),
                        request.revisions().getFirst()));
                String expected = request.revisions().get(1);
                String fetched = git(List.of(
                        "--git-dir=" + mirrorPath(key),
                        "rev-parse",
                        "--quiet",
                        "--verify",
                        "--end-of-options",
                        expected + "^{commit}"));
                if (!fetched.equals(expected))
                    throw new IllegalStateException("Fetched ref does not carry the pinned commit");
                return;
            }
            Path operation = Files.createTempDirectory(root, "operation-");
            try {
                Path result = operation.resolve("output");
                Path repositoryRoot = Path.of("").toAbsolutePath();
                while (!Files.isRegularFile(repositoryRoot.resolve("docker/agents/git/operation.ts"))) {
                    Path parent = repositoryRoot.getParent();
                    if (parent == null) throw new IllegalStateException("Native Git test helper not found");
                    repositoryRoot = parent;
                }
                Path helper = repositoryRoot.resolve("docker/agents/git/operation.ts");
                Path error = operation.resolve("stderr");
                ProcessBuilder builder = new ProcessBuilder("node", helper.toString());
                builder.environment().put("GIT_REPOSITORY_DIRECTORY", gitDirectory.toString());
                builder.environment()
                        .put(
                                "GIT_SNAPSHOT_DIRECTORY",
                                operation.resolve("snapshot").toString());
                builder.environment().put("GIT_TEMP_DIRECTORY", operation.toString());
                builder.redirectOutput(result.toFile()).redirectError(error.toFile());
                Process process = builder.start();
                try {
                    try (var stdin = process.getOutputStream()) {
                        stdin.write(mapper.writeValueAsBytes(request));
                    }
                    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                        throw new IllegalStateException("Native Git fixture timed out");
                    if (process.exitValue() != 0) {
                        try (var errors = Files.newInputStream(error)) {
                            throw new IllegalStateException("Native Git fixture failed: "
                                    + new String(
                                            errors.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    try (var stream = Files.newInputStream(result)) {
                        stream.transferTo(output);
                    }
                } finally {
                    process.destroyForcibly();
                }
            } finally {
                removeTree(operation);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void deleteRepository(long repositoryId) {
        try (var scopes = Files.list(root)) {
            for (Path scope : scopes.filter(
                            path -> path.getFileName().toString().endsWith("-" + repositoryId))
                    .toList()) removeTree(scope);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the command's trimmed stdout; a non-zero exit fails the fixture */
    private static String git(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            String output;
            try (var stdout = process.getInputStream()) {
                output = new String(stdout.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException("Git fixture preparation failed");
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
