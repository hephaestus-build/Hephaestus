package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A review's sandbox, as its driver and a stub of the Pi runner would act. It is the claims API that the server
 * requests sandboxes through, so it holds each sandbox's token and gateway; {@link #drive} then pulls each sandbox's run
 * and measures it over HTTP, as the container does: it reads its workspace from the gateway, starts each assigned
 * practice, asks the review model once through the proxy, stages a result for each practice that started, seals them,
 * uploads its output, and ends its run. Only the model's judgement is a stub: each started practice is MET, citing the
 * pull request's title. In a scan, {@link #judgeFiles} decides each file for each practice instead, and the sandbox
 * pulls each file's run in turn.
 */
final class ReviewSandboxStub implements SandboxClaims {

    /** What the runner did, in order; a test reads it to see what reached the gateway. */
    record Call(String path, int status, String body) {}

    /** The model's judgement of one file for one practice: met, or not met with one problem on its first line. */
    record Judgement(@Nullable String problem) {
        static Judgement met() {
            return new Judgement(null);
        }

        static Judgement notMet(String problem) {
            return new Judgement(problem);
        }
    }

    /** A file that a scan's sandbox judged for a practice. */
    record Judged(String practiceSlug, String path) {}

    private static final String TREE = "scm.repository.tree";

    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Set<String> measured = ConcurrentHashMap.newKeySet();
    private volatile Runnable beforeStart = () -> {};
    /** Where the next measurement loses its server. */
    private enum Loss {
        AFTER_FIRST_START,
        BEFORE_SEAL,
        /** The sandbox stops after its first staged result, with no upload and no end, as a crashed replica leaves it. */
        AFTER_FIRST_STAGE
    }

    private final AtomicReference<@Nullable Loss> loss = new AtomicReference<>();
    private volatile Consumer<String> afterLostAttempt = runId -> {};
    private volatile @Nullable BiFunction<String, String, Judgement> files;
    final List<Judged> judged = new CopyOnWriteArrayList<>();

    ReviewSandboxStub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void prepare(Runnable betweenCaptureAndStart) {
        calls.clear();
        beforeStart = betweenCaptureAndStart;
    }

    /**
     * The sandbox measures scans: it judges each assigned file with {@code judgement}, given the practice's slug and
     * the file's path, and reads the pinned file completely, as a scan's MET needs.
     */
    void judgeFiles(BiFunction<String, String, Judgement> judgement) {
        calls.clear();
        judged.clear();
        files = judgement;
    }

    /**
     * The next measurement loses its server after its first practice starts, as a hard restart of the only replica
     * does: the runner waits out the refused gateway until its work ends, exits retryable, and uploads that exit once
     * the server is back. The sandbox then pulls the review's next attempt.
     *
     * @param afterUpload reads what the lost attempt left, given its run's id
     */
    void loseGatewayDuringNextMeasurement(Consumer<String> afterUpload) {
        afterLostAttempt = afterUpload;
        loss.set(Loss.AFTER_FIRST_START);
    }

    /**
     * The next measurement loses its server after its practice starts and before it seals, as a hard restart of the
     * only replica does. Unlike {@link #loseGatewayDuringNextMeasurement}, this holds also when every start was withheld.
     *
     * @param afterUpload reads what the lost attempt left, given its run's id
     */
    void loseGatewayBeforeNextSeal(Consumer<String> afterUpload) {
        afterLostAttempt = afterUpload;
        loss.set(Loss.BEFORE_SEAL);
    }

    /**
     * The next measurement stops after its first staged result. It uploads nothing and does not end its run, so the
     * run keeps its lease until it expires, as when the replica that held the sandbox crashes.
     *
     * @param afterCrash ends what the crash left, given its run's id
     */
    void crashAfterNextStage(Consumer<String> afterCrash) {
        afterLostAttempt = afterCrash;
        loss.set(Loss.AFTER_FIRST_STAGE);
    }

    @Override
    public void create(Claim claim) {
        claims.put(claim.name(), claim);
    }

    @Override
    public void delete(String name) {
        claims.remove(name);
    }

    @Override
    public List<String> names() {
        return List.copyOf(claims.keySet());
    }

    @Override
    public Optional<PodEnd> podEnd(String name) {
        return Optional.empty();
    }

    /**
     * Pulls the due run of each sandbox, as its driver does, and measures its review once. The composition that follows
     * runs on the composer's own kind, which its own test drives; this sandbox pulls no more.
     */
    void drive() throws IOException, InterruptedException {
        for (Claim claim : claims.values()) {
            if (!measured.add(claim.name())) continue;
            String gateway = Objects.requireNonNull(claim.env().get("HEPHAESTUS_GATEWAY_URL"));
            String token = Objects.requireNonNull(claim.env().get("HEPHAESTUS_SANDBOX_TOKEN"));
            var offered = http.send(
                    HttpRequest.newBuilder(URI.create(gateway + "/internal/agent/next"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (offered.statusCode() != 200) {
                measured.remove(claim.name());
                continue;
            }
            JsonNode run = mapper.readTree(offered.body());
            if (!run.path("kind").asString().equals("REVIEW"))
                throw new IllegalStateException("A review's sandbox first measures its review: " + run);
            String runId = run.path("runId").asString();
            Map<String, String> headers = Map.of(
                    "Authorization",
                    "Bearer " + token,
                    "X-Agent-Run",
                    runId,
                    "X-Agent-Epoch",
                    Integer.toString(run.path("epoch").asInt()));
            String base = gateway + "/internal/agent/runs/" + runId;
            var context = send(base + "/context", headers, null);
            if (context.statusCode() == 409) {
                // The attempt ended while it prepared, with nothing left to measure. The runner stops on a 409.
                calls.add(new Call("context", 409, new String(context.body(), StandardCharsets.UTF_8)));
                continue;
            }
            Loss lost = measure(gateway + "/internal/llm", base + "/runtime", headers);
            if (lost != Loss.AFTER_FIRST_STAGE) {
                var ended = send(
                        base + "/end",
                        headers,
                        mapper.writeValueAsBytes(mapper.createObjectNode().put("outcome", "completed")));
                calls.add(new Call("end", ended.statusCode(), new String(ended.body(), StandardCharsets.UTF_8)));
            }
            if (lost != null) {
                measured.remove(claim.name());
                afterLostAttempt.accept(runId);
            } else if (files != null) measured.remove(claim.name());
        }
    }

    /**
     * The runner's review: {@code runReviewJob} in {@code pi-runner.ts}, with a fixed judgement.
     *
     * @return where it lost its server, or null when it measured
     */
    private @Nullable Loss measure(String proxy, String runtime, Map<String, String> token)
            throws IOException, InterruptedException {
        Map<String, byte[]> workspace =
                untar(send(runtime + "/workspace", token, null).body());
        JsonNode task = mapper.readTree(workspace.get(SandboxLayout.TASK_ENVELOPE_FILENAME));
        JsonNode manifest = mapper.readTree(workspace.get(SandboxLayout.MANIFEST_PATH));
        var scan = files;
        @Nullable String record = scan == null ? coreRecordPath(manifest, workspace) : null;
        List<JsonNode> assigned = new ArrayList<>();
        workspace.forEach((path, bytes) -> {
            if (path.startsWith(SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX)
                    && path.endsWith(SandboxLayout.PRACTICE_SCRIPT_INPUTS_SUFFIX)) assigned.add(mapper.readTree(bytes));
        });
        beforeStart.run();

        var results = mapper.createArrayNode();
        for (JsonNode inputs : assigned) {
            String slug = inputs.path("practice").path("slug").asString();
            var started = post(
                    proxy + "/start-practice", token, mapper.createObjectNode().put("practiceSlug", slug));
            if (!mapper.readTree(started).path("current").asBoolean()) continue;
            if (loss.compareAndSet(Loss.AFTER_FIRST_START, null)) {
                upload(runtime, token, Map.of(), SandboxLayout.EXIT_SERVER_UNREACHABLE);
                return Loss.AFTER_FIRST_START;
            }
            // As the runner's model calls do: the proxy serves only streamed chat calls.
            var completion = mapper.createObjectNode()
                    .put("model", "review")
                    .put("max_tokens", 16)
                    .put("stream", true);
            completion.putArray("messages").addObject().put("role", "user").put("content", "Review " + slug);
            post(proxy + "/chat/completions", token, completion);
            var result = results.addObject()
                    .put("practiceSlug", slug)
                    .put("itemId", inputs.path("scope").path("item").path("id").asString())
                    .put("itemVersion", inputs.path("scope").path("version").asString());
            var receipts = result.putArray("readReceipts");
            if (scan == null) {
                String path = Objects.requireNonNull(record);
                result.set("candidate", met(path, Objects.requireNonNull(workspace.get(path))));
            } else {
                String path = inputs.path("scope").path("item").path("path").asString();
                judged.add(new Judged(slug, path));
                result.set("candidate", file(manifest, workspace, path, scan.apply(slug, path), receipts));
            }
            post(
                    proxy + "/stage-result",
                    token,
                    admission(task, mapper.createArrayNode().add(result.deepCopy())));
            if (loss.compareAndSet(Loss.AFTER_FIRST_STAGE, null)) return Loss.AFTER_FIRST_STAGE;
        }
        if (loss.compareAndSet(Loss.BEFORE_SEAL, null)) {
            upload(runtime, token, Map.of(), SandboxLayout.EXIT_SERVER_UNREACHABLE);
            return Loss.BEFORE_SEAL;
        }
        post(proxy + "/admit-results", token, envelope(task));

        // As the runner does, a review whose practices each started or were withheld at their start writes its result,
        // which is empty when every start was withheld.
        upload(runtime, token, Map.of("result.json", mapper.writeValueAsBytes(admission(task, results))), 0);
        return null;
    }

    /** What gateway-run does after the runner exits: it uploads the output with the runner's exit. */
    private void upload(String runtime, Map<String, String> token, Map<String, byte[]> files, int exitCode)
            throws IOException, InterruptedException {
        byte[] output = tar(files);
        var upload = HttpRequest.newBuilder(URI.create(runtime + "/result"));
        token.forEach(upload::header);
        var uploaded = http.send(
                upload.header("Content-Type", "application/x-tar")
                        .header("x-sandbox-exit-code", Integer.toString(exitCode))
                        .header("x-sandbox-timed-out", "false")
                        .header(
                                "Content-Digest",
                                "sha-256=:" + Base64.getEncoder().encodeToString(sha256(output)) + ":")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(output))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        calls.add(new Call("result", uploaded.statusCode(), uploaded.body()));
        if (uploaded.statusCode() != 204) throw new IllegalStateException("The result upload failed: " + uploaded);
    }

    private ObjectNode envelope(JsonNode task) {
        return mapper.createObjectNode()
                .put("contractVersion", 3)
                .put("reviewId", task.path("reviewId").asString())
                .put("jobId", task.path("jobId").asString())
                .put("attempt", task.path("attempt").asInt());
    }

    private ObjectNode admission(JsonNode task, ArrayNode results) {
        var request = envelope(task);
        request.set("results", results);
        request.putArray("nonResults");
        return request;
    }

    /** A MET judgement that cites the pull request's title line from the captured record. */
    private ObjectNode met(String path, byte[] record) {
        String[] lines = new String(record, StandardCharsets.UTF_8).split("\n", -1);
        int line = 0;
        while (!lines[line].contains("\"title\"")) line++;
        var candidate = mapper.createObjectNode()
                .put("outcome", "MET")
                .put("summary", "The pull request states what it changes")
                .put("explanation", "The pull request's title names the change, so a reader knows what it does.");
        candidate
                .putArray("citations")
                .addObject()
                .put("sourceKind", "scm.pull-request.core")
                .put("sourcePath", path)
                .put("path", path)
                .put("startLine", line + 1)
                .put("endLine", line + 1)
                .put("quote", lines[line]);
        candidate.putObject("evidence");
        candidate
                .putObject("coverage")
                .put("source", "host")
                .put("reads", 1)
                .put("truncatedReads", 0)
                .put("failedReads", 0)
                .put("complete", true)
                .putArray("receipts");
        return candidate;
    }

    /**
     * A scan's judgement of its assigned file, as the runner stages it: one complete read of the pinned file, and a
     * citation of its first line, on the result when it is met and on its problem when it is not.
     */
    private ObjectNode file(
            JsonNode manifest, Map<String, byte[]> workspace, String path, Judgement judgement, ArrayNode receipts) {
        @Nullable String witness = null;
        for (JsonNode file : manifest.path("files"))
            if (witness == null && file.path("kind").asString().equals(TREE))
                witness = file.path("file").path("path").asString();
        @Nullable JsonNode text = null;
        for (JsonNode captured : manifest.path("citableTexts"))
            if (captured.path("source").asString().equals(TREE)
                    && captured.path("path").asString().equals(path)
                    && captured.path("commit").isString()) text = captured;
        if (witness == null || text == null)
            throw new IllegalStateException("The capture holds no pinned text of " + path + ": " + manifest);
        String commit = text.path("commit").asString();
        byte[] bytes = Objects.requireNonNull(
                workspace.get(text.path("stagedPath").asString()), "The workspace holds no copy of " + path);
        String quote = new String(bytes, StandardCharsets.UTF_8).split("\n", 2)[0];
        receipts.addObject()
                .put("sourceKind", TREE)
                .put("sourcePath", witness)
                .put("path", path)
                .put("version", commit)
                .put("status", "COMPLETE")
                .put("contentDigest", text.path("sha256").asString());
        @Nullable String problemSummary = judgement.problem();
        var candidate = mapper.createObjectNode()
                .put("outcome", problemSummary == null ? "MET" : "NOT_MET")
                .put("summary", problemSummary == null ? "The file meets the practice" : problemSummary)
                .put("explanation", "The file's first line shows it.");
        var citations = candidate.putArray("citations");
        var citation = mapper.createObjectNode()
                .put("sourceKind", TREE)
                .put("sourcePath", witness)
                .put("path", path)
                .put("commit", commit)
                .put("startLine", 1)
                .put("endLine", 1)
                .put("quote", quote);
        if (problemSummary == null) citations.add(citation);
        else {
            var problem = candidate
                    .putArray("problems")
                    .addObject()
                    .put("severity", "MAJOR")
                    .put("summary", problemSummary)
                    .put("explanation", "The file's first line does not meet the criterion.");
            problem.putArray("citations").add(citation);
            problem.putObject("basis").put("type", "state").put("explanation", "The file is in this state now.");
        }
        candidate.putObject("evidence");
        var coverage = candidate
                .putObject("coverage")
                .put("source", "host")
                .put("reads", receipts.size())
                .put("truncatedReads", 0)
                .put("failedReads", 0)
                .put("complete", true);
        coverage.set("receipts", receipts.deepCopy());
        return candidate;
    }

    /** The captured pull request record: the file of the core source that names the title. */
    private static String coreRecordPath(JsonNode manifest, Map<String, byte[]> workspace) {
        for (JsonNode source : manifest.path("sources"))
            if (source.path("kind").asString().equals("scm.pull-request.core"))
                for (JsonNode file : source.path("files")) {
                    String path = file.path("path").asString();
                    byte[] bytes = workspace.get(path);
                    if (bytes != null && new String(bytes, StandardCharsets.UTF_8).contains("\"title\"")) return path;
                }
        throw new IllegalStateException("The capture holds no pull request record: " + manifest);
    }

    private String post(String url, Map<String, String> token, JsonNode body) throws IOException, InterruptedException {
        var response = send(url, token, mapper.writeValueAsBytes(body));
        String text = new String(response.body(), StandardCharsets.UTF_8);
        calls.add(new Call(url.substring(url.lastIndexOf('/') + 1), response.statusCode(), text));
        if (response.statusCode() != 200) throw new IllegalStateException(url + " answered " + response + ": " + text);
        return text;
    }

    private HttpResponse<byte[]> send(String url, Map<String, String> headers, byte @Nullable [] body)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        headers.forEach(request::header);
        request = body == null
                ? request.GET()
                : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Map<String, byte[]> untar(byte[] archive) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
                if (entry.isFile()) files.put(entry.getName().replaceFirst("^\\./", ""), tar.readAllBytes());
        }
        return files;
    }

    private static byte[] tar(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                var entry = new TarArchiveEntry("out/" + file.getKey());
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
