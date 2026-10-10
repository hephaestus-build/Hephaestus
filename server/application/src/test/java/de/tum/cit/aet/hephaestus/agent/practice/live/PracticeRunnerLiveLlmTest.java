package de.tum.cit.aet.hephaestus.agent.practice.live;

import static de.tum.cit.aet.hephaestus.agent.practice.live.PracticeCriteriaCaseFixtures.CHANGE_VIEW_PREFIX;
import static de.tum.cit.aet.hephaestus.agent.practice.live.PracticeCriteriaCaseFixtures.FIXTURE_DIR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import de.tum.cit.aet.hephaestus.testconfig.PiSdkInstallation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Live end-to-end test for the practice-review {@code pi-runner.ts} against a real LLM.
 *
 * <p>Exercises Pi SDK ↔ LLM, the runner's two-attempt loop, watchdog, custom
 * {@code report_observation} tool, and the result schema the runner emits — all without Docker.
 * The {@code DockerSandboxLiveTest} covers the sandbox SPI separately.
 *
 * <p>Mirrors {@code MentorLiveLlmTest} for the Pi SDK install and the custom provider
 * extension that bends Pi's built-in {@code openai} provider toward the configured endpoint (Pi does not
 * read {@code OPENAI_BASE_URL} natively).
 *
 * <p>Workspace staging differs from the mentor test: the practice runner hardcodes
 * {@code /workspace} as CWD and reads/writes via async fs (read tool) and {@code spawn} (bash
 * tool). Patching every fs entry point in a Node shim is fragile, so we stage at {@code
 * /workspace} directly. The harness runs as root and the directory is cleaned between tests.
 */
@LiveLlmTest
@Tag("live")
class PracticeRunnerLiveLlmTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path RUNNER =
            Path.of("src", "main", "resources", "agent", "pi-runner.ts").toAbsolutePath();

    /** The runner reads/writes here verbatim; we own the directory for the duration of one test. */
    // Somewhere writable, rather than a root-owned /workspace this harness may not be able to create.
    private static final Path WORKSPACE =
            Path.of(System.getenv().getOrDefault("PI_RUNNER_CWD", "/workspace")).toAbsolutePath();

    /** Wall-clock cap for the whole runner — initial + retry budgets are derived from this. */
    private static final long AGENT_BUDGET_MS = 240_000L;

    @BeforeAll
    static void installPiSdk() throws Exception {
        PiSdkInstallation.ensureInstalled();
    }

    @BeforeEach
    void cleanWorkspace() throws IOException {
        // The runner hard-codes /workspace; we own it exclusively (@LiveLlmTest pins to SAME_THREAD).
        // Per-test isolation: clear everything before each run so leftovers from a failed previous
        // attempt cannot satisfy result.json checks.
        if (Files.exists(WORKSPACE)) {
            deleteTree(WORKSPACE);
        }
        Files.createDirectories(WORKSPACE);
    }

    @AfterEach
    void leaveWorkspaceArtifacts() {
        // Intentionally do NOT delete /workspace here. If the test fails, the next run's @BeforeEach
        // will clean — but a developer hand-running locally keeps the .output files for diagnostics.
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void flagsHardcodedSecret_inOneFileDiff() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        try (ProxyStandIn proxy = new ProxyStandIn(creds)) {
            runAndVerify(creds, proxy, null);
        }
    }

    @ParameterizedTest(name = CriteriaCaseSelection.NAME)
    @MethodSource(CriteriaCaseSelection.SOURCE)
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void shouldApplyCatalogueBoundaryWhenEvidenceMatchesCase(JsonNode scenario) throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        try (ProxyStandIn proxy = new ProxyStandIn(creds)) {
            runAndVerify(creds, proxy, scenario);
        }
    }

    private void runAndVerify(LiveLlmCredentials creds, ProxyStandIn proxy, @Nullable JsonNode scenario)
            throws Exception {
        stageWorkspace(creds);
        if (scenario != null) {
            PracticeCriteriaCaseFixtures.stage(WORKSPACE, scenario);
        }
        // This is a measurement harness, not a delivery test. Keeping the composition request absent
        // means the runner cannot even prepare feedback, and spawnRunner strips every ambient credential
        // before the agent process starts. In particular, a developer's GH_TOKEN/GITHUB_TOKEN is never
        // exposed to the model's bash tool.
        assertThat(Files.exists(WORKSPACE.resolve(SandboxLayout.FEEDBACK_COMPOSITION_PATH)))
                .as("live harness does not request feedback composition")
                .isFalse();
        Process runner = spawnRunner(proxy.baseUrl());

        // Drain stdout/stderr into the JVM console with a tag so failures show what the agent said.
        Thread stdoutPump = pumpStream(runner.getInputStream(), "[practice-runner stdout]");
        Thread stderrPump = pumpStream(runner.getErrorStream(), "[practice-runner stderr]");

        boolean finished = runner.waitFor(240, TimeUnit.SECONDS);
        if (!finished) {
            runner.destroyForcibly();
            stdoutPump.join(5_000);
            stderrPump.join(5_000);
            fail("pi-runner did not exit within 240s — likely an LLM stall or watchdog miss. Check "
                    + "the [practice-runner *] output above for diagnostics.");
            return;
        }
        stdoutPump.join(5_000);
        stderrPump.join(5_000);

        int exitCode = runner.exitValue();

        // The runner returns 0 on success, 1 on retry-budget exhaustion. Anything else (2 fatal,
        // 3 watchdog kill, 42 envelope mismatch) is a real bug.
        if (exitCode != 0 && exitCode != 1) {
            fail("pi-runner exited with unexpected code " + exitCode
                    + " (0=success, 1=retry-exhausted, 2=fatal, 3=watchdog, 42=envelope mismatch). "
                    + "See [practice-runner *] output above.");
        }

        // Parse via the real production parser. The parser tolerates fallback through review-state.json
        // and swallows malformed JSON — assertions below are what catches a real regression.
        SandboxResult sandboxResult = buildSandboxResult(exitCode);
        AgentResult result = new PiResultParser(MAPPER, new SimpleMeterRegistry()).parse(sandboxResult);

        assertThat(exitCode).as("the review completed within its budget").isZero();
        assertThat(result.success()).isTrue();

        String rawOutput = (String) result.output().get("rawOutput");
        assertThat(rawOutput)
                .as("result.json (or review-state.json fallback) yielded a parsed payload")
                .isNotNull();

        JsonNode parsed = MAPPER.readTree(rawOutput);
        JsonNode observations = parsed.path("observations");
        assertThat(observations.isArray())
                .as("observations must be a JSON array")
                .isTrue();
        assertThat(observations.size()).as("at least one observation emitted").isGreaterThanOrEqualTo(1);

        Map<String, String> expected = new LinkedHashMap<>();
        if (scenario == null) {
            expected.put("avoids-insecure-defaults-and-over-broad-permissions", "NOT_MET");
        } else {
            scenario.path("expected")
                    .properties()
                    .forEach(entry ->
                            expected.put(entry.getKey(), entry.getValue().asString()));
        }
        Map<String, String> expectedSeverity = new LinkedHashMap<>();
        if (scenario != null) {
            scenario.path("expectedSeverity")
                    .properties()
                    .forEach(entry -> expectedSeverity.put(
                            entry.getKey(), entry.getValue().asString()));
        }
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> actualSeverity = new LinkedHashMap<>();
        for (JsonNode observation : observations) {
            String slug = observation.path("practiceSlug").asString();
            assertThat(actual.put(slug, observation.path("outcome").asString()))
                    .as("one observation per practice")
                    .isNull();
            if (expectedSeverity.containsKey(slug)) {
                actualSeverity.put(slug, observation.path("severity").asString());
            }
            if ("NOT_MET".equals(observation.path("outcome").asString())) {
                assertThat(observation.path("severity").asString()).isIn("CRITICAL", "MAJOR", "MINOR", "INFO");
            } else {
                assertThat(observation.path("severity").isNull()).isTrue();
            }
            assertThat(observation.has("confidence")).isFalse();
            assertThat(observation.path("evidence").path("citations").isArray()).isTrue();
        }
        assertThat(actual)
                .as(
                        "%s: %s",
                        scenario == null
                                ? "hardcoded secret"
                                : scenario.path("id").asString(),
                        rawOutput)
                .isEqualTo(expected);
        assertThat(actualSeverity)
                .as(
                        "severity in %s: %s",
                        scenario == null
                                ? "hardcoded secret"
                                : scenario.path("id").asString(),
                        rawOutput)
                .isEqualTo(expectedSeverity);

        // Usage diagnostics — surfaces token totals to the console so flakes show whether the call
        // even reached the LLM. Not asserted as the watchdog branch may leave usage=0.
        AgentResult.LlmUsage usage = result.usage();
        if (usage != null) {
            System.out.printf(
                    "[practice-live] usage: model=%s, input=%s, output=%s, totalCalls=%d, cost=$%s%n",
                    usage.model(), usage.inputTokens(), usage.outputTokens(), usage.totalCalls(), usage.costUsd());
        }
        System.out.printf("[practice-live] %d observation(s)%n", observations.size());
    }

    private void stageWorkspace(LiveLlmCredentials creds) throws IOException {
        // ESM resolution walks node_modules upward from the importing file. Production binds the
        // global node_modules into /workspace/node_modules; the test does the same with a symlink.
        Path nodeModulesLink = WORKSPACE.resolve("node_modules");
        if (Files.exists(nodeModulesLink) || Files.isSymbolicLink(nodeModulesLink)) {
            Files.delete(nodeModulesLink);
        }
        Files.createSymbolicLink(nodeModulesLink, PiSdkInstallation.SDK_DIR.resolve("node_modules"));

        // Copy the production runner verbatim — same bytes that ship to the agent container.
        Files.copy(RUNNER, WORKSPACE.resolve("pi-runner.ts"), StandardCopyOption.REPLACE_EXISTING);
        for (String sidecar : new PracticeRunnerProfile().sidecarScripts()) {
            Files.copy(
                    Path.of("src", "main", "resources", "agent", sidecar),
                    WORKSPACE.resolve(sidecar),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // Orchestrator instructions live at WORKSPACE/.pi/AGENTS.md — same layout production uses,
        // with PI_CODING_AGENT_DIR pointed inside the workspace.
        Path piDir = WORKSPACE.resolve(SandboxLayout.PI_AGENT_PREFIX);
        Files.createDirectories(piDir);
        Files.copy(
                Path.of("src", "main", "resources", "agent", "pi-orchestrator.md")
                        .toAbsolutePath(),
                piDir.resolve(SandboxLayout.ORCHESTRATOR_FILENAME),
                StandardCopyOption.REPLACE_EXISTING);

        // The same pi-provider.json contract production uses, not a parallel models.json that could drift.
        Files.createDirectories(WORKSPACE.resolve(".home"));
        Files.write(piDir.resolve("settings.json"), buildSettingsJson(creds.model()));
        Files.write(WORKSPACE.resolve("pi-provider.json"), buildProviderConfigJson(creds));

        // Practice catalog under /workspace/inputs/practices/ — the agent reads index.json (slug list)
        // and each practice's own criteria file per the orchestrator instructions.
        Path practicesDir = WORKSPACE.resolve(SandboxLayout.PRACTICES_PREFIX);
        Files.createDirectories(practicesDir);
        copyFixture("practices/index.json", practicesDir.resolve("index.json"));
        copyFixture(
                "practices/avoids-insecure-defaults-and-over-broad-permissions.md",
                practicesDir.resolve("avoids-insecure-defaults-and-over-broad-permissions.md"));

        // Context fixture — the pinned change, metadata, comments. Mirrors what
        // PullRequestContentSource materialises in production.
        Path contextDir = WORKSPACE.resolve(SandboxLayout.CONTEXT_PREFIX);
        Files.createDirectories(contextDir);
        // The folder index: every citation is checked against it; the runner refuses to start without it.
        copyFixture("INDEX.json", WORKSPACE.resolve(SandboxLayout.MANIFEST_PATH));
        copyFixture("change.json", contextDir.resolve("change.json"));
        copyFixture("metadata.json", contextDir.resolve("metadata.json"));
        copyFixture("comments.json", contextDir.resolve("comments.json"));

        // The change view pi-change.ts derives from the checkout with git before the runner starts.
        // This harness spawns the runner alone over a repo mount that is not a git repository, so the
        // annotated diff is a fixture staged where the runner reads a citation of the change from.
        Path changeDir = WORKSPACE.resolve(CHANGE_VIEW_PREFIX);
        Files.createDirectories(changeDir);
        copyFixture("diff.patch", changeDir.resolve("diff.patch"));

        // Real Swift source so the agent's read tool can pull the actual bytes when grepping the
        // repo mount (we mount the captured repository path as a symlink to the
        // fixture directory so grep+read work without an actual git clone).
        Path repoLink = WORKSPACE.resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
        Files.createDirectories(repoLink.getParent());
        if (Files.exists(repoLink) || Files.isSymbolicLink(repoLink)) {
            Files.delete(repoLink);
        }
        Files.createSymbolicLink(repoLink, FIXTURE_DIR);

        // Task envelope at /workspace/task.json — built via the production TaskEnvelope record so
        // any future field addition fails this test at compile time, not at runtime.
        TaskEnvelope envelope = TaskEnvelope.of(
                UUID.randomUUID(),
                1L,
                new Task(
                        "Review merge request #1 in test/fixture. Read inputs/practices/index.json, "
                                + "inputs/practices/avoids-insecure-defaults-and-over-broad-permissions.md, and context/metadata.json. "
                                + "Apply the avoids-insecure-defaults-and-over-broad-permissions practice to "
                                + CHANGE_VIEW_PREFIX
                                + "diff.patch. Persist each "
                                + "justified observation via report_observation (one tool call per observation). Follow "
                                + SandboxLayout.ORCHESTRATOR_PATH
                                + " for the schema and review rules.",
                        1,
                        "test/fixture"));
        Files.write(
                WORKSPACE.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
    }

    private static byte[] buildSettingsJson(String modelId) throws IOException {
        // Duplicates PiRuntimeFactory.buildPiSettingsJson: this harness stages files without a PiPlan.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("defaultProvider", "hephaestus");
        settings.put("defaultModel", modelId);
        settings.put("transport", "sse");
        Map<String, Object> compaction = new HashMap<>();
        compaction.put("enabled", true);
        compaction.put("reserveTokens", 16384);
        settings.put("compaction", compaction);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
    }

    private static byte[] buildProviderConfigJson(LiveLlmCredentials creds) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiProtocol", "openai-completions");
        config.put("modelId", creds.model());
        config.put("contextWindow", 131072);
        config.put("maxOutputTokens", 4096);
        config.put("supportsReasoning", false);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
    }

    private static void copyFixture(String relativePath, Path dest) throws IOException {
        Files.copy(FIXTURE_DIR.resolve(relativePath), dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Process spawnRunner(String proxyUrl) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("node", "pi-runner.ts");
        pb.directory(WORKSPACE.toFile());
        Map<String, String> env = pb.environment();
        // Fail closed: the agent has a bash tool, so inheriting the developer's shell environment would
        // also inherit SCM, cloud, database, and deployment credentials. The live test needs only the
        // model proxy plus basic process environment. This makes an accidental GitHub write impossible
        // even when the invoking shell is authenticated.
        String path = env.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.clear();
        env.put("PATH", path);
        env.put("HOME", WORKSPACE.resolve(".home").toString());
        env.put("LANG", "C.UTF-8");
        // The only name carried through the clear() above.
        env.put("PI_RUNNER_CWD", WORKSPACE.toString());
        // The stand-in holds the live key, as the server's proxy does; the runner never sees it.
        env.put("LLM_PROXY_URL", proxyUrl);
        env.put("LLM_PROXY_TOKEN", "live-test-job-token");
        // PI_CODING_AGENT_DIR points Pi at the staged orchestrator and settings, away from ~/.pi.
        env.put(
                "PI_CODING_AGENT_DIR",
                WORKSPACE.resolve(SandboxLayout.PI_AGENT_PREFIX).toString());
        env.put("AGENT_BUDGET_MS", Long.toString(AGENT_BUDGET_MS));
        // The server's defaults (PracticeReviewProperties): the work each practice is owed per turn.
        env.put("PI_PRACTICE_MODEL_CALLS", "12");
        env.put("PI_PRACTICE_OUTPUT_TOKENS", "16000");
        // The runner imports the SDK via bare ESM specifiers; NODE_PATH lets Node resolve them when
        // /workspace/node_modules is a symlink (some Node versions skip symlinked node_modules).
        env.put("NODE_PATH", PiSdkInstallation.SDK_DIR.resolve("node_modules").toString());
        return pb.start();
    }

    private static Thread pumpStream(InputStream stream, String tag) {
        Thread t = new Thread(
                () -> {
                    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println(tag + " " + line);
                        }
                    } catch (IOException ignored) {
                        // Stream closed when the child exits — nothing to do.
                    }
                },
                "practice-runner-log-" + tag.hashCode());
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Build a {@link SandboxResult} from the on-disk {@code out/} directory. Mirrors what
     * {@code DockerSandboxAdapter} does after a container exit so we feed the production parser
     * the exact map shape it expects.
     */
    private static SandboxResult buildSandboxResult(int exitCode) throws IOException {
        Map<String, byte[]> outputFiles = new LinkedHashMap<>();
        Path outputDir = WORKSPACE.resolve("out");
        if (Files.isDirectory(outputDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        outputFiles.put(entry.getFileName().toString(), Files.readAllBytes(entry));
                    }
                }
            }
        }
        return new SandboxResult(exitCode, outputFiles, "", false, Duration.ZERO);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        // Manual walk to avoid Files.walk + lambda surprises with symlinks (we have node_modules + repo).
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.forEach(entries::add);
        }
        // Reverse: children before parents.
        for (int i = entries.size() - 1; i >= 0; i--) {
            Path p = entries.get(i);
            if (p.equals(root)) {
                continue;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // Symlink targets may be unreadable; remove the link itself.
                if (Files.isSymbolicLink(p)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /**
     * Stands in for the server's LLM proxy on one base URL, as production does: model calls are forwarded to
     * the live endpoint with its key, and the observation admission the runner posts after measuring admits
     * every observation it carries.
     */
    static final class ProxyStandIn implements AutoCloseable {
        private final HttpServer server;
        private final HttpClient client = HttpClient.newHttpClient();

        ProxyStandIn(LiveLlmCredentials creds) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/admit-observations", exchange -> {
                byte[] body = MAPPER.writeValueAsBytes(admit(MAPPER.readTree(exchange.getRequestBody())));
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.createContext("/", exchange -> forward(exchange, creds));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** Every observation admitted as sent, with the identity and citation indexes the server assigns. */
        static ObjectNode admit(JsonNode request) {
            ObjectNode answer = MAPPER.createObjectNode().put("schemaVersion", 1);
            ArrayNode admitted = answer.putArray("observations");
            for (JsonNode observation : request.path("observations")) {
                ObjectNode copy = (ObjectNode) observation.deepCopy();
                copy.put("id", UUID.randomUUID().toString());
                copy.put("outcome", outcomeOf(observation));
                ArrayNode citations = copy.putArray("citations");
                int index = 0;
                for (JsonNode citation : observation.path("evidence").path("citations")) {
                    citations.add(((ObjectNode) citation.deepCopy()).put("index", index++));
                }
                admitted.add(copy);
            }
            return answer.put("admissionDigest", "live-test");
        }

        /** As admission records it: the outcome as sent, which must name an {@link Outcome}. */
        private static String outcomeOf(JsonNode observation) {
            JsonNode outcome = observation.path("outcome");
            if (!outcome.isString()) {
                throw new IllegalArgumentException("An admitted observation needs a string outcome");
            }
            return Outcome.valueOf(outcome.asString()).name();
        }

        private void forward(HttpExchange exchange, LiveLlmCredentials creds) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            creds.baseUrl() + exchange.getRequestURI().getPath()))
                    .header("authorization", "Bearer " + creds.apiKey())
                    .header("content-type", "application/json")
                    .method(
                            exchange.getRequestMethod(),
                            HttpRequest.BodyPublishers.ofByteArray(
                                    exchange.getRequestBody().readAllBytes()))
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                response.headers()
                        .firstValue("content-type")
                        .ifPresent(type -> exchange.getResponseHeaders().set("content-type", type));
                // Chunked: a streamed completion reaches the runner as it arrives.
                exchange.sendResponseHeaders(response.statusCode(), 0);
                try (var in = response.body();
                        var out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(502, -1);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            client.close();
        }
    }
}
