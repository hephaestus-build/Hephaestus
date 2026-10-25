package de.tum.cit.aet.hephaestus.agent.practice.live;

import static de.tum.cit.aet.hephaestus.agent.practice.live.PracticeCriteriaCaseFixtures.FIXTURE_DIR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedProblemDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateProgressDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.ProjectedCitationDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimBasis;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
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
import tools.jackson.databind.node.ObjectNode;

// Pi does not read OPENAI_BASE_URL; the harness must configure its provider extension explicitly.
@LiveLlmTest
@Tag("live")
@Slf4j
class PracticeRunnerLiveLlmTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** The runner reads/writes here verbatim; we own the directory for the duration of one test. */
    // Somewhere writable, rather than a root-owned /workspace this harness may not be able to create.
    private static final Path WORKSPACE =
            Path.of(System.getenv().getOrDefault("PI_RUNNER_CWD", "/workspace")).toAbsolutePath();

    private static final String PRACTICE = "avoids-insecure-defaults-and-over-broad-permissions";

    @BeforeAll
    static void installPiSdk() throws Exception {
        // An invalid request limit fails here, before the SDK install, the credentials or any model request.
        ProxyStandIn.requestLimit(System.getProperty(ProxyStandIn.REQUEST_LIMIT_PROPERTY));
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
    @Timeout(value = LiveRunnerWorkspace.TIMEOUT_SECONDS + 30, unit = TimeUnit.SECONDS)
    void flagsHardcodedSecret_inOneFileDiff() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        try (ProxyStandIn proxy = new ProxyStandIn(creds)) {
            runAndVerify(creds, proxy, null);
        }
    }

    @ParameterizedTest(name = CriteriaCaseSelection.NAME)
    @MethodSource(CriteriaCaseSelection.SOURCE)
    @Timeout(value = LiveRunnerWorkspace.TIMEOUT_SECONDS + 30, unit = TimeUnit.SECONDS)
    void shouldApplyCatalogueBoundaryWhenEvidenceMatchesCase(JsonNode scenario) throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        try (ProxyStandIn proxy = new ProxyStandIn(creds)) {
            runAndVerify(creds, proxy, scenario);
        }
    }

    private void runAndVerify(LiveLlmCredentials creds, ProxyStandIn proxy, @Nullable JsonNode scenario)
            throws Exception {
        Map<String, String> environment = stageWorkspace(creds, scenario);
        // Live measurements run without a composition request or ambient credential to prevent provider feedback
        // writes.
        assertThat(Files.exists(WORKSPACE.resolve(SandboxLayout.FEEDBACK_COMPOSITION_PATH)))
                .as("live harness does not request feedback composition")
                .isFalse();
        Process runner =
                LiveRunnerWorkspace.spawn(WORKSPACE, environment, proxy.baseUrl(), "live-test-job-token", null);

        // Drain stdout/stderr into the JVM console with a tag so failures show what the agent said.
        Thread stdoutPump = pumpStream(runner.getInputStream(), "[practice-runner stdout]");
        Thread stderrPump = pumpStream(runner.getErrorStream(), "[practice-runner stderr]");

        // The process needs the job's shutdown buffer after its own model budget ends.
        boolean finished = runner.waitFor(LiveRunnerWorkspace.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            runner.destroyForcibly();
            stdoutPump.join(5_000);
            stderrPump.join(5_000);
            fail("pi-runner did not exit within " + LiveRunnerWorkspace.TIMEOUT_SECONDS
                    + "s. Check "
                    + "the [practice-runner *] output above for diagnostics.");
            return;
        }
        stdoutPump.join(5_000);
        stderrPump.join(5_000);

        // Before the exit code and the result: a refused model request fails the case even if the result matches.
        proxy.assertRequestLimitNotSpent();

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
        assertThat(parsed.path("contractVersion").asInt())
                .as("result.json is the admission body")
                .isEqualTo(SandboxLayout.PRACTICE_CONTRACT_VERSION);
        JsonNode practiceResults = parsed.path("results");
        assertThat(practiceResults.isArray()).as("results must be a JSON array").isTrue();
        assertThat(practiceResults.size()).as("at least one result emitted").isGreaterThanOrEqualTo(1);

        Map<String, String> expected = new LinkedHashMap<>();
        if (scenario == null) {
            expected.put(PRACTICE, "NOT_MET");
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
        for (JsonNode entry : practiceResults) {
            String slug = entry.path("practiceSlug").asString();
            JsonNode candidate = entry.path("candidate");
            assertThat(actual.put(slug, candidate.path("outcome").asString()))
                    .as("one result per practice")
                    .isNull();
            String severity = ProxyStandIn.storedSeverity(candidate);
            if (expectedSeverity.containsKey(slug)) {
                actualSeverity.put(slug, String.valueOf(severity));
            }
            if ("NOT_MET".equals(candidate.path("outcome").asString())) {
                assertThat(severity).isIn("CRITICAL", "MAJOR", "MINOR", "TRIVIAL");
                assertThat(candidate.path("problems"))
                        .as("a negative locates its problems")
                        .isNotEmpty();
            } else {
                assertThat(candidate.has("severity")).isFalse();
                assertThat(candidate.has("problems")).isFalse();
            }
            assertThat(candidate.has("confidence")).isFalse();
            assertThat(candidate.path("citations").isArray()).isTrue();
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
        System.out.printf("[practice-live] %d practiceResult(s)%n", practiceResults.size());
    }

    private Map<String, String> stageWorkspace(LiveLlmCredentials creds, @Nullable JsonNode scenario) throws Exception {
        PracticeSandboxSpec spec = LiveRunnerWorkspace.adapter()
                .buildSandboxSpec(LiveRunnerWorkspace.request(creds.model(), "live-test-job-token", Map.of()));
        LiveRunnerWorkspace.stageSpec(WORKSPACE, spec.inputFiles());
        String kind = "scm.pull_request";
        Map<String, JsonNode> definitions;
        if (scenario == null) {
            LiveRunnerWorkspace.stageLoginChange(WORKSPACE);
            definitions = Map.of(
                    PRACTICE,
                    LiveRunnerWorkspace.stagePractice(
                            WORKSPACE,
                            PRACTICE,
                            "Hardcoded Secrets",
                            Files.readString(FIXTURE_DIR.resolve("practices/" + PRACTICE + ".md")),
                            1L));
            LiveRunnerWorkspace.stageTask(
                    WORKSPACE,
                    "Review merge request #1 in test/fixture against " + PRACTICE + ". Follow "
                            + SandboxLayout.ORCHESTRATOR_PATH + ".",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    1L);
        } else {
            definitions = PracticeCriteriaCaseFixtures.stage(WORKSPACE, scenario);
            if (scenario.path("workType").asString().equals("issue")) kind = "scm.issue";
        }
        LiveRunnerWorkspace.stageFixtureInputs(WORKSPACE, kind, definitions);
        return spec.environment();
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
     * {@code SandboxAttemptRunner} does after a container exit so we feed the production parser
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
     * the live endpoint with its key, and the result admission the runner posts after measuring admits
     * every result it carries.
     */
    static final class ProxyStandIn implements AutoCloseable {
        /**
         * The whole wait for forwarding work and the upstream client. Their own close() waits for every
         * unfinished response stream, so a stalled upstream would hold a runner assertion in cleanup until
         * the JUnit timeout replaces it.
         */
        private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

        /**
         * The most upstream model requests one stand-in sends. Absent means no limit. A sent request keeps its slot
         * whatever its outcome, so an error, a dropped connection, a retry or a cancelled call cannot buy another.
         */
        static final String REQUEST_LIMIT_PROPERTY = "hephaestus.live.criteriaRequestLimit";

        /** What a request past the limit receives instead of a model answer: a client error a client does not retry. */
        private static final byte[] REQUEST_LIMIT_SPENT = ("{\"error\":{\"type\":\"request_budget_exhausted\","
                        + "\"message\":\"This live measurement has sent every model request its request limit allows.\"}}")
                .getBytes(StandardCharsets.UTF_8);

        private final OptionalInt requestLimit;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final Object lifecycle = new Object();
        // Guarded by lifecycle, so close() cannot miss a forwarding that registers concurrently.
        private final Set<Forwarding> active = new HashSet<>();
        private boolean closed;
        // Guarded by lifecycle. Never decremented: a slot stays spent once its request may be sent.
        private int sentRequests;
        // Guarded by lifecycle. Any refusal fails the measurement, whatever result the run then writes.
        private int refusedRequests;

        ProxyStandIn(LiveLlmCredentials creds) throws IOException {
            // Read before the client and the listener exist, so an invalid limit starts nothing.
            requestLimit = requestLimit(System.getProperty(REQUEST_LIMIT_PROPERTY));
            client = HttpClient.newHttpClient();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/admit-results", exchange -> {
                byte[] body = MAPPER.writeValueAsBytes(admit(MAPPER.readTree(exchange.getRequestBody())));
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.createContext("/", exchange -> forward(exchange, creds));
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** The request limit named by {@code value}, a positive {@code int}; absent when the value is. */
        static OptionalInt requestLimit(@Nullable String value) {
            if (value == null) {
                return OptionalInt.empty();
            }
            int limit;
            try {
                limit = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        REQUEST_LIMIT_PROPERTY + " must be a positive integer, not '" + value + "'", e);
            }
            if (limit < 1) {
                throw new IllegalArgumentException(
                        REQUEST_LIMIT_PROPERTY + " must be a positive integer, not '" + value + "'");
            }
            return OptionalInt.of(limit);
        }

        /**
         * Fails when the request limit refused any model request. A refused request means the run did not get the
         * model calls it asked for, so a result it wrote anyway does not count as a measurement.
         */
        void assertRequestLimitNotSpent() {
            int sent;
            int refused;
            synchronized (lifecycle) {
                sent = sentRequests;
                refused = refusedRequests;
            }
            assertThat(refused)
                    .as(
                            "model requests refused after the request limit of %s was spent (%d sent)",
                            requestLimit.isPresent() ? String.valueOf(requestLimit.getAsInt()) : "none", sent)
                    .isZero();
        }

        /**
         * Every candidate admitted as sent, projected as the server answers it: the result and each of its problems
         * under the ids the server assigns, every citation indexed, nothing withheld and no line anchorable.
         */
        static ObjectNode admit(JsonNode request) {
            var submitted = MAPPER.treeToValue(request, AdmissionRequestDTO.class);
            var rows = new ArrayList<AdmittedResultDTO>();
            for (var entry : submitted.results()) {
                JsonNode candidate = entry.candidate();
                UUID resultId = UUID.randomUUID();
                var problems = new ArrayList<AdmittedProblemDTO>();
                for (JsonNode problem : candidate.path("problems")) {
                    problems.add(new AdmittedProblemDTO(
                            UUID.randomUUID(),
                            Severity.valueOf(problem.path("severity").asString()),
                            problem.path("summary").asString(),
                            resultId,
                            problems.size(),
                            null,
                            problem.path("explanation").asString(),
                            projected(problem.path("citations")),
                            MAPPER.treeToValue(problem.path("basis"), ClaimBasis.class),
                            problem.has("nextStep") ? problem.path("nextStep").asString() : null,
                            false));
                }
                ObjectNode evidence = (ObjectNode) candidate.path("evidence").deepCopy();
                evidence.set("citations", MAPPER.valueToTree(projected(candidate.path("citations"))));
                rows.add(new AdmittedResultDTO(
                        entry.practiceSlug(),
                        entry.itemId(),
                        resultId,
                        ResultOutcome.valueOf(outcomeOf(candidate)),
                        candidate.path("summary").asString(),
                        candidate.path("explanation").asString(),
                        evidence,
                        candidate.has("example") ? candidate.get("example") : null,
                        MAPPER.treeToValue(candidate.path("coverage"), ResultCoverage.class),
                        problems,
                        false,
                        false));
            }
            return MAPPER.valueToTree(new AdmissionResponseDTO(
                    AdmissionResponseDTO.CONTRACT_VERSION,
                    submitted.reviewId(),
                    submitted.jobId(),
                    submitted.attempt(),
                    ProvenanceDigest.sha256Hex(MAPPER.writeValueAsBytes(request)),
                    rows,
                    List.of(),
                    null,
                    null,
                    null,
                    false,
                    new PrivateProgressDTO(List.of(), 0, 0)));
        }

        private static String outcomeOf(JsonNode candidate) {
            JsonNode outcome = candidate.path("outcome");
            if (!outcome.isString()) {
                throw new IllegalArgumentException("An admitted result needs a string outcome");
            }
            return ResultOutcome.valueOf(outcome.asString()).name();
        }

        static @Nullable String storedSeverity(JsonNode candidate) {
            Severity worst = null;
            for (JsonNode problem : candidate.path("problems")) {
                Severity severity = Severity.valueOf(problem.path("severity").asString());
                if (worst == null || severity.rank() < worst.rank()) worst = severity;
            }
            return worst == null ? null : worst.name();
        }

        private static List<ProjectedCitationDTO> projected(JsonNode citations) {
            var projected = new ArrayList<ProjectedCitationDTO>();
            for (JsonNode value : citations) {
                var citation = MAPPER.treeToValue(value, EvidenceCitation.class);
                projected.add(new ProjectedCitationDTO(
                        citation.sourceKind().value(),
                        citation.sourcePath(),
                        citation.path(),
                        citation.side(),
                        citation.commit(),
                        citation.startLine(),
                        citation.endLine(),
                        citation.quote(),
                        citation.verification(),
                        projected.size(),
                        false));
            }
            return List.copyOf(projected);
        }

        private void forward(HttpExchange exchange, LiveLlmCredentials creds) throws IOException {
            Forwarding forwarding = new Forwarding();
            if (!register(forwarding)) {
                exchange.close();
                return;
            }
            int requestId = System.identityHashCode(exchange);
            long started = System.nanoTime();
            log.info("Live upstream request {} started", requestId);
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                // The slot is taken once the whole request is here and just before the one upstream send.
                Admission admission = reserve();
                if (admission == Admission.CLOSED) {
                    return;
                }
                if (admission == Admission.REFUSED) {
                    log.info("Live upstream request {} refused: the request limit is spent", requestId);
                    exchange.getResponseHeaders().set("content-type", "application/json");
                    exchange.sendResponseHeaders(400, REQUEST_LIMIT_SPENT.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(REQUEST_LIMIT_SPENT);
                    }
                    return;
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                creds.baseUrl() + exchange.getRequestURI().getPath()))
                        .header("authorization", "Bearer " + creds.apiKey())
                        .header("content-type", "application/json")
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                try {
                    HttpResponse<InputStream> response =
                            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    log.info(
                            "Live upstream request {} headers: status={}, elapsedMs={}",
                            requestId,
                            response.statusCode(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    try (InputStream upstream = response.body()) {
                        if (!attach(forwarding, upstream)) {
                            // close() took its snapshot before this stream arrived.
                            return;
                        }
                        response.headers()
                                .firstValue("content-type")
                                .ifPresent(type -> exchange.getResponseHeaders().set("content-type", type));
                        // Forward the response with chunked transfer encoding.
                        exchange.sendResponseHeaders(response.statusCode(), 0);
                        try (var out = exchange.getResponseBody()) {
                            upstream.transferTo(out);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(502, -1);
                }
            } finally {
                log.info(
                        "Live upstream request {} ended: elapsedMs={}",
                        requestId,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                synchronized (lifecycle) {
                    active.remove(forwarding);
                }
            }
        }

        private boolean register(Forwarding forwarding) {
            synchronized (lifecycle) {
                return !closed && active.add(forwarding);
            }
        }

        /** Takes a slot for one upstream send. Nothing ever returns it. */
        private Admission reserve() {
            synchronized (lifecycle) {
                if (closed) {
                    return Admission.CLOSED;
                }
                if (requestLimit.isPresent() && sentRequests >= requestLimit.getAsInt()) {
                    refusedRequests++;
                    return Admission.REFUSED;
                }
                sentRequests++;
                return Admission.SEND;
            }
        }

        private boolean attach(Forwarding forwarding, InputStream upstream) {
            synchronized (lifecycle) {
                if (closed) {
                    return false;
                }
                forwarding.upstream = upstream;
                return true;
            }
        }

        /**
         * Stops forwarding within {@link #CLOSE_TIMEOUT}. Every cancellation stage runs even when an earlier one
         * fails, and blocking work stays outside the lifecycle lock. A resource that has not terminated by the
         * deadline fails the close; under try-with-resources an earlier assertion stays primary.
         */
        @Override
        public void close() {
            List<InputStream> streams = new ArrayList<>();
            synchronized (lifecycle) {
                if (closed) {
                    return;
                }
                closed = true;
                for (Forwarding forwarding : active) {
                    if (forwarding.upstream != null) {
                        streams.add(forwarding.upstream);
                    }
                }
            }
            long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
            List<Exception> failures = new ArrayList<>();
            try {
                // Stop closes connections; the handler alone closes each response body and exchange.
                server.stop(0);
            } catch (RuntimeException e) {
                failures.add(e);
            }
            try {
                executor.shutdownNow();
            } catch (RuntimeException e) {
                failures.add(e);
            }
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException | RuntimeException e) {
                    failures.add(e);
                }
            }
            try {
                client.shutdownNow();
            } catch (RuntimeException e) {
                failures.add(e);
            }
            try {
                if (!executor.awaitTermination(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
                    failures.add(new IllegalStateException("forwarding work did not stop within " + CLOSE_TIMEOUT));
                }
                if (!client.awaitTermination(remaining(deadline))) {
                    failures.add(new IllegalStateException("the upstream client did not stop within " + CLOSE_TIMEOUT));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(e);
            }
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("The proxy stand-in did not shut down");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        private static Duration remaining(long deadline) {
            return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
        }

        private enum Admission {
            SEND,
            REFUSED,
            CLOSED
        }

        /** Once the upstream answers, the response stream transferred by one forwarding handler. */
        private static final class Forwarding {
            // Guarded by the stand-in's lifecycle lock.
            private @Nullable InputStream upstream;
        }
    }
}
