package de.tum.cit.aet.hephaestus.agent.practice.live;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class PracticeCriteriaCaseFixtures {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final Path FIXTURE_DIR =
            Path.of("src/test/resources/agent/live-practice").toAbsolutePath();

    /** Where {@code pi-change.ts} writes the change view ({@code CHANGE_ROOT} there). */
    static final String CHANGE_VIEW_PREFIX = "work/change/";

    private PracticeCriteriaCaseFixtures() {}

    static void stage(Path workspace, JsonNode scenario) throws IOException, InterruptedException {
        Path repo = workspace.resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
        Files.deleteIfExists(repo);
        Files.createDirectories(repo);
        Files.createDirectories(workspace.resolve(".home"));
        Files.writeString(workspace.resolve(SandboxLayout.NODE_PACKAGE_JSON_FILENAME), "{\"type\":\"module\"}\n");
        Files.createDirectories(workspace.resolve(SandboxLayout.PRACTICES_PREFIX));
        Files.createDirectories(workspace.resolve("context/docs"));
        Files.writeString(workspace.resolve("context/description.md"), "");
        Files.writeString(workspace.resolve("context/comments.json"), "[]\n");
        fixtureCommand(workspace, "git", "init", repo.toString());
        fixtureCommand(workspace, "git", "-C", repo.toString(), "config", "user.name", "Example Developer");
        fixtureCommand(workspace, "git", "-C", repo.toString(), "config", "user.email", "developer@example.org");
        fixtureCommand(workspace, "git", "-C", repo.toString(), "config", "commit.gpgsign", "false");
        fixtureCommand(workspace, "git", "-C", repo.toString(), "commit", "--allow-empty", "-m", "Start the fixture");
        String base = fixtureCommand(workspace, "git", "-C", repo.toString(), "rev-parse", "HEAD")
                .trim();
        var manifest = MAPPER.createObjectNode();
        ArrayNode artifacts = manifest.putArray("artifacts");
        String coreKind =
                scenario.path("workType").asString().equals("issue") ? "scm.issue.core" : "scm.pull-request.core";
        for (var entry : scenario.path("files").properties()) {
            boolean repositoryFile = entry.getKey().startsWith("repo/");
            String relative = repositoryFile
                    ? SandboxLayout.REPO_MOUNT_RELATIVE + entry.getKey().substring("repo/".length())
                    : entry.getKey();
            Path file = workspace.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().asString());
            if (!relative.equals("context/description.md")) {
                artifacts
                        .addObject()
                        .put("kind", repositoryFile ? "scm.repository.tree" : coreKind)
                        .putObject("artifact")
                        .put("path", relative);
            }
        }
        fixtureCommand(workspace, "git", "-C", repo.toString(), "add", "--all");
        fixtureCommand(workspace, "git", "-C", repo.toString(), "commit", "--allow-empty", "-m", "Add captured files");
        String head = fixtureCommand(workspace, "git", "-C", repo.toString(), "rev-parse", "HEAD")
                .trim();
        Files.write(
                workspace.resolve("context/change.json"),
                MAPPER.writeValueAsBytes(Map.of("base_sha", base, "head_sha", head)));
        var metadata = (ObjectNode)
                MAPPER.readTree(FIXTURE_DIR.resolve("metadata.json").toFile());
        metadata.put("title", "Add the change shown in the captured files");
        metadata.put("commit_sha", head);
        metadata.put("body", Files.readString(workspace.resolve("context/description.md")));
        Files.write(workspace.resolve("context/metadata.json"), MAPPER.writeValueAsBytes(metadata));

        JsonNode catalogue = MAPPER.readTree(
                Path.of("src/main/resources/practices/default-catalog.json").toFile());
        Path practices = workspace.resolve(SandboxLayout.PRACTICES_PREFIX);
        try (var existing = Files.list(practices)) {
            for (Path file : existing.toList()) {
                Files.delete(file);
            }
        }
        ArrayNode index = MAPPER.createArrayNode();
        for (JsonNode group : catalogue.path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                String slug = practice.path("slug").asString();
                if (!scenario.path("expected").has(slug)) {
                    continue;
                }
                index.addObject()
                        .put("slug", slug)
                        .put("name", practice.path("name").asString());
                String preamble = practice.path("preamble")
                        .asString(coreKind.equals("scm.issue.core") ? "scm.issue" : "scm.pull_request");
                String criteria =
                        catalogue.path("criteriaPreambles").path(preamble).asString() + "\n\n---\n\n"
                                + practice.path("criteria").asString();
                Files.writeString(practices.resolve(slug + ".md"), criteria);
            }
        }
        Files.write(workspace.resolve(SandboxLayout.PRACTICES_PREFIX + "index.json"), MAPPER.writeValueAsBytes(index));
        TaskEnvelope envelope = TaskEnvelope.of(
                UUID.randomUUID(),
                1L,
                new Task(
                        "Review the captured " + scenario.path("workType").asString()
                                + " in context/description.md, context/metadata.json, "
                                + "context/docs/ and work/change/diff.patch. The checkout is "
                                + SandboxLayout.REPO_MOUNT_RELATIVE + ". "
                                + "Read inputs/practices/index.json and each listed practice. "
                                + "Record one justified outcome for each listed practice with report_observation. "
                                + "Follow " + SandboxLayout.ORCHESTRATOR_PATH + ".",
                        1,
                        "test/fixture"));
        Files.write(workspace.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME), MAPPER.writeValueAsBytes(envelope));
        fixtureCommand(workspace, "node", "pi-change.ts", workspace.toString());
        JsonNode files = MAPPER.readTree(
                        workspace.resolve(CHANGE_VIEW_PREFIX + "files.json").toFile())
                .path("files");
        Files.write(
                workspace.resolve("context/commits.json"),
                MAPPER.writeValueAsBytes(Map.of(
                        "commits",
                        List.of(Map.of(
                                "sha",
                                head,
                                "message",
                                "Add captured files",
                                "parents",
                                List.of(base),
                                "files",
                                files)))));
        Map<String, String> contents = Map.of(
                coreKind,
                "NON_EMPTY",
                "scm.pull-request.diff",
                Files.size(workspace.resolve(CHANGE_VIEW_PREFIX + "diff.patch")) == 0 ? "EMPTY" : "NON_EMPTY",
                "scm.pull-request.comments",
                "EMPTY",
                "scm.pull-request.commits",
                "NON_EMPTY",
                "scm.repository.tree",
                files.isEmpty() ? "EMPTY" : "NON_EMPTY");
        for (var source : contents.entrySet()) {
            manifest.withArray("sources")
                    .addObject()
                    .put("kind", source.getKey())
                    .putObject("state")
                    .put("availability", "AVAILABLE")
                    .put("content", source.getValue());
        }
        for (String file : List.of("diff.patch", "files.json", "description.authored.md")) {
            artifacts
                    .addObject()
                    .put("kind", "scm.pull-request.diff")
                    .putObject("artifact")
                    .put("path", CHANGE_VIEW_PREFIX + file);
        }
        for (String file : List.of("description.md", "metadata.json")) {
            artifacts.addObject().put("kind", coreKind).putObject("artifact").put("path", "context/" + file);
        }
        artifacts
                .addObject()
                .put("kind", "scm.pull-request.diff")
                .putObject("artifact")
                .put("path", "context/change.json");
        artifacts
                .addObject()
                .put("kind", "scm.pull-request.comments")
                .putObject("artifact")
                .put("path", "context/comments.json");
        artifacts
                .addObject()
                .put("kind", "scm.pull-request.commits")
                .putObject("artifact")
                .put("path", "context/commits.json");
        Files.write(workspace.resolve(SandboxLayout.MANIFEST_PATH), MAPPER.writeValueAsBytes(manifest));
    }

    static String fixtureCommand(Path workspace, String... command) throws IOException, InterruptedException {
        Path log = Files.createTempFile(workspace, "fixture-command-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            String path = builder.environment().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
            builder.environment().clear();
            builder.environment().put("PATH", path);
            builder.environment().put("HOME", workspace.resolve(".home").toString());
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IOException(
                            "Fixture command timed out: " + String.join(" ", command) + "\n" + Files.readString(log));
                }
                String output = Files.readString(log);
                if (process.exitValue() != 0) {
                    throw new IOException("Fixture command failed (exit " + process.exitValue() + "): "
                            + String.join(" ", command) + "\n" + output);
                }
                return output;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly().waitFor();
                }
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
