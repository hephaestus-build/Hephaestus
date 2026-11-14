package de.tum.cit.aet.hephaestus.agent.practice.live;

import static de.tum.cit.aet.hephaestus.agent.practice.live.PracticeCriteriaCaseFixtures.FIXTURE_DIR;
import static de.tum.cit.aet.hephaestus.agent.practice.live.PracticeCriteriaCaseFixtures.fixtureCommand;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeCatalogInjector;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeScriptInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.DockerStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Real tools and practice programs run in the agent image; only this test's owned workspace is mounted.
final class LiveRunnerWorkspace {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveRunnerWorkspace.class);

    static final Path TOOLKIT = Path.of("..", "..", "docker", "agents", "practice-scripts", "lib")
            .toAbsolutePath()
            .normalize();

    /** The job timeout of a live run: the runner's own budget is this minus the shutdown buffer. */
    static final int TIMEOUT_SECONDS = 300;

    private LiveRunnerWorkspace() {}

    static String image() {
        String reference = System.getProperty(
                "hephaestus.live.agent-image",
                System.getenv().getOrDefault("HEPHAESTUS_LIVE_AGENT_IMAGE", "hephaestus/agent-pi:live"));
        if (reference.isBlank()) throw new IllegalArgumentException("The live agent image reference is empty");
        return reference;
    }

    static void requireImage() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image()).exec();
        } catch (NotFoundException missing) {
            throw new IllegalStateException(
                    "Build or load the selected live agent image before qualification: " + image(), missing);
        }
    }

    static PracticePiAdapter adapter() {
        return new PracticePiAdapter(
                new PiRuntimeFactory(MAPPER),
                new PiResultParser(MAPPER, new SimpleMeterRegistry()),
                new AgentImageProperties(image(), ImagePullPolicy.NEVER),
                // The shipped defaults: a live run must get the budget that a review gets.
                new Binder(List.of()).bindOrCreate("hephaestus.practice-review", PracticeReviewProperties.class));
    }

    static PracticeAgentRequest request(String modelId, String jobToken, LiveModelSettings settings) {
        return new PracticeAgentRequest(
                "openai-completions",
                modelId,
                settings.contextWindow(),
                settings.maxOutputTokens(),
                settings.reasoningEffort(),
                jobToken,
                TIMEOUT_SECONDS,
                false);
    }

    static void stageSpec(Path workspace, Map<String, byte[]> inputFiles) throws IOException {
        for (Map.Entry<String, byte[]> file : inputFiles.entrySet()) {
            Path target = workspace.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        // ESM resolution walks node_modules upward from the importing file; production binds the SDK there.
        link(workspace.resolve("node_modules"), Path.of("/opt/pi-sdk/node_modules"));
        Path toolkit = workspace.resolve(SandboxLayout.PRACTICE_SCRIPT_LIBRARY_LINK);
        Files.createDirectories(toolkit.getParent());
        link(toolkit, Path.of(SandboxLayout.PRACTICE_SCRIPT_LIBRARY_TARGET));
        link(
                workspace.resolve(SandboxLayout.PRACTICE_SCRIPT_KITS_LINK),
                Path.of(SandboxLayout.PRACTICE_SCRIPT_KITS_TARGET));
    }

    static final Path CART_CHANGE = Path.of("..", "..", "docker", "agents", "practice-scripts", "fixtures")
            .resolve("cart-change.diff")
            .toAbsolutePath()
            .normalize();

    /** The captured witnesses of the reviewed checkout, under {@code scm.repository.tree}: admission needs both. */
    static final List<String> CHECKOUT_WITNESSES = List.of(
            SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD",
            SandboxLayout.REPO_MOUNT_RELATIVE + ".git/" + GitRepositoryManager.CAPTURED_REFS);

    static ObjectNode stageLoginChange(Path workspace) throws IOException, InterruptedException {
        return stageChange(
                workspace,
                Map.of(),
                Map.of("LoginService.swift", Files.readString(FIXTURE_DIR.resolve("LoginService.swift"))),
                "Add login service");
    }

    static ObjectNode stageCartChange(Path workspace) throws IOException, InterruptedException {
        Map<String, String> base = new LinkedHashMap<>();
        Map<String, String> head = new LinkedHashMap<>();
        String library = Files.readString(TOOLKIT.resolveSibling("kits").resolve("scm/diff-parser.ts"));
        base.put("lib/diff-parser.ts", library);
        head.put("lib/diff-parser.ts", library);
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        String path = null;
        for (String line : Files.readAllLines(CART_CHANGE)) {
            if (line.startsWith("+++ b/")) {
                path = line.substring("+++ b/".length());
                continue;
            }
            if (line.startsWith("diff ") || line.startsWith("--- ") || line.startsWith("@@")) continue;
            if (line.startsWith("+")) after.append(line.substring(1)).append('\n');
            else if (line.startsWith("-")) before.append(line.substring(1)).append('\n');
            else if (line.startsWith(" ")) {
                before.append(line.substring(1)).append('\n');
                after.append(line.substring(1)).append('\n');
            }
        }
        if (path == null) throw new IllegalStateException("The change names no file: " + CART_CHANGE);
        base.put(path, before.toString());
        head.put(path, after.toString());
        return stageChange(workspace, base, head, "Count the items in the cart");
    }

    /**
     * A real checkout of the reviewed change, captured as the server captures it: the head checked out detached, its
     * {@link #CHECKOUT_WITNESSES}, the pinned change, the record ({@link LiveWorkRecords}), the comments and the folder
     * index that names them.
     *
     * @return the record staged as {@code context/metadata.json}
     */
    static ObjectNode stageChange(Path workspace, Map<String, String> base, Map<String, String> head, String title)
            throws IOException, InterruptedException {
        Path repo = workspace.resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
        Files.createDirectories(repo);
        git(workspace, "init", "-q", repo.toString());
        writeFiles(repo, base);
        git(workspace, "-C", repo.toString(), "add", "--all");
        git(workspace, "-C", repo.toString(), "commit", "-q", "--allow-empty", "-m", "Start the fixture");
        String baseSha =
                git(workspace, "-C", repo.toString(), "rev-parse", "HEAD").strip();
        writeFiles(repo, head);
        git(workspace, "-C", repo.toString(), "add", "--all");
        git(workspace, "-C", repo.toString(), "commit", "-q", "-m", title);
        String headSha =
                git(workspace, "-C", repo.toString(), "rev-parse", "HEAD").strip();
        // The capture checks the head out detached and lists the refs the container may see; the fixture has none.
        git(workspace, "-C", repo.toString(), "checkout", "-q", "--detach", headSha);
        Files.writeString(repo.resolve(".git/" + GitRepositoryManager.CAPTURED_REFS), "");
        int additions = 0;
        for (Map.Entry<String, String> file : head.entrySet()) {
            if (!file.getValue().equals(base.get(file.getKey()))) {
                additions += (int) file.getValue().lines().count();
            }
        }

        Path context = workspace.resolve(SandboxLayout.CONTEXT_PREFIX);
        Files.createDirectories(context);
        Files.write(
                context.resolve("change.json"),
                MAPPER.writeValueAsBytes(Map.of("base_sha", baseSha, "head_sha", headSha)));
        ObjectNode record = LiveWorkRecords.pullRequest(headSha, title, "", additions);
        Files.write(context.resolve("metadata.json"), LiveWorkRecords.staged(record));
        Files.copy(FIXTURE_DIR.resolve("comments.json"), context.resolve("comments.json"));
        Files.write(
                workspace.resolve(SandboxLayout.MANIFEST_PATH),
                MAPPER.writeValueAsBytes(manifest(workspace, capturedFiles())));
        return record;
    }

    static String treeIdentity(Path workspace) throws IOException, InterruptedException {
        String repo = workspace.resolve(SandboxLayout.REPO_MOUNT_RELATIVE).toString();
        return git(workspace, "-C", repo, "rev-parse", "HEAD").strip() + ":"
                + git(workspace, "-C", repo, "rev-parse", "HEAD^{tree}").strip();
    }

    static Map<String, String> capturedFiles() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put(SandboxLayout.CONTEXT_PREFIX + "metadata.json", "scm.pull-request.core");
        inputs.put(SandboxLayout.CONTEXT_PREFIX + "change.json", "scm.pull-request.diff");
        inputs.put(SandboxLayout.CONTEXT_PREFIX + "comments.json", "scm.pull-request.comments");
        CHECKOUT_WITNESSES.forEach(witness -> inputs.put(witness, "scm.repository.tree"));
        return inputs;
    }

    static ObjectNode manifest(Path workspace, Map<String, String> files) throws IOException {
        var snapshot = EvidenceSnapshotFixtures.snapshot(MAPPER);
        var sources = new LinkedHashMap<String, ObjectNode>();
        for (var entry : files.entrySet()) {
            byte[] bytes = Files.readAllBytes(workspace.resolve(entry.getKey()));
            var source = sources.computeIfAbsent(
                    entry.getValue(), kind -> EvidenceSnapshotFixtures.availableSource(snapshot, kind, null));
            EvidenceSnapshotFixtures.file(snapshot, source, entry.getKey(), ProvenanceDigest.sha256Hex(bytes))
                    .put("bytes", bytes.length);
        }
        return snapshot.withObject("manifest");
    }

    private static void writeFiles(Path repo, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = repo.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    static JsonNode stagePractice(Path workspace, String slug, String name, String criteria, long versionId)
            throws IOException {
        Path practices = workspace.resolve(SandboxLayout.PRACTICES_PREFIX);
        Files.createDirectories(practices);
        var base = DefinitionTestFixtures.definition(new ItemKind("scm.pull_request"), name, criteria, null);
        var definition = new PracticeDefinition(
                base.name(),
                base.reviewOn(),
                base.appliesWhen(),
                base.about(),
                new PracticeReads(
                        ClasspathSourceCatalogRegistry.CURRENT_VERSION.value(),
                        List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.diff"), ReadNeed.COMPLETE),
                                new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.OPTIONAL))),
                base.reviewedBy(),
                base.criteria(),
                base.script(),
                base.scan(),
                base.lineNotes(),
                base.guidance());
        var entry = MAPPER.createObjectNode()
                .put("slug", slug)
                .put("name", name)
                .put("versionId", Long.toString(versionId));
        var exhaustive = entry.putArray("exhaustiveSources");
        var limits = entry.putArray("knownLimitations");
        definition.reviewedBy().limits().forEach(limit -> limits.add(limit.description()));
        var reads = entry.putArray("readsSources");
        definition.reads().sources().forEach(source -> {
            reads.add(source.source().value());
            if (source.need() == ReadNeed.COMPLETE)
                exhaustive.add(source.source().value());
        });
        Files.write(practices.resolve("index.json"), MAPPER.writeValueAsBytes(List.of(entry)));
        Files.writeString(practices.resolve(slug + ".md"), criteria);
        return MAPPER.valueToTree(definition);
    }

    static void stageFixtureInputs(Path workspace, String kind, Map<String, JsonNode> definitions) throws IOException {
        var task = MAPPER.readValue(
                workspace.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME).toFile(), TaskEnvelope.class);
        var work = MAPPER.readTree(workspace
                .resolve(SandboxLayout.CONTEXT_PREFIX + "metadata.json")
                .toFile());
        var item = new ItemRef(Long.parseLong(task.workspaceId()), 1, new ItemKind(kind), 1L, null, null);
        ItemVersion version;
        if (kind.equals("scm.pull_request")) {
            var change = MAPPER.readTree(workspace
                    .resolve(SandboxLayout.CONTEXT_PREFIX + "change.json")
                    .toFile());
            version = ScmVersion.pullRequest(
                    change.path("base_sha").asString(),
                    change.path("head_sha").asString(),
                    work.path("title").asString(),
                    work.path("body").isNull() ? null : work.path("body").asString());
        } else version = ScmVersion.ofContentDigest(work.toString());
        var index = MAPPER.readTree(
                workspace.resolve(SandboxLayout.PRACTICES_PREFIX + "index.json").toFile());
        long practiceId = 0;
        var assignments = new ArrayList<ReviewAssignmentSnapshot>();
        var staged = new LinkedHashMap<String, byte[]>();
        for (var entry : index) {
            practiceId++;
            var definition =
                    MAPPER.treeToValue(definitions.get(entry.path("slug").asString()), PracticeDefinition.class);
            long versionId = Long.parseLong(entry.path("versionId").asString());
            var pinned = new PinnedPractice(
                    practiceId, versionId, definition, "a".repeat(64), new ReviewMethod(1, "b".repeat(64), List.of()));
            var assignment = new ReviewAssignmentSnapshot(
                    new ReviewAssignmentId(task.reviewId(), practiceId, 1),
                    item,
                    version,
                    definition.about() == null ? null : 1L,
                    entry.path("slug").asString(),
                    pinned);
            assignments.add(assignment);
            var review = new ReviewSnapshot(
                    task.reviewId(),
                    item.workspaceId(),
                    item,
                    ReviewKind.ITEM,
                    version,
                    ReviewOrigin.REQUEST,
                    1,
                    List.of(pinned));
            staged.putAll(new PracticeScriptInputs(MAPPER).stage(review, assignment, work, LiveWorkRecords.AT, null));
        }
        new PracticeCatalogInjector(MAPPER).inject(staged, assignments, ReviewKind.ITEM, Map.of());
        for (var file : staged.entrySet()) {
            Path path = workspace.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }
    }

    /** The task envelope, built from the production record so that a new field fails here at compile time. */
    static void stageTask(Path workspace, String prompt, UUID reviewId, UUID jobId, int attempt, long workspaceId)
            throws IOException {
        TaskEnvelope envelope = TaskEnvelope.of(reviewId, jobId, attempt, workspaceId, new Task(prompt));
        Files.write(
                workspace.resolve(SandboxLayout.TASK_ENVELOPE_FILENAME),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
    }

    /** The caller owns the container, including a startup failure or a run that passes its deadline. */
    static GenericContainer<?> runner(Path workspace, PracticeSandboxSpec spec, String proxyUrl, String jobToken)
            throws IOException {
        String user = workspaceUser(workspace);
        URI proxy = proxyInContainer(proxyUrl);
        // Testcontainers tunnels only this listener, including a listener bound to loopback on Linux.
        Testcontainers.exposeHostPorts(proxy.getPort());
        Map<String, String> environment = new HashMap<>(spec.environment());
        environment.put("PI_RUNNER_CWD", SandboxLayout.WORKSPACE_ROOT);
        environment.put("LLM_PROXY_URL", proxy.toString());
        environment.put("LLM_PROXY_TOKEN", jobToken);
        // Neither the stand-in proxy nor a review attempt's token reads the claim; the runner refuses to start
        // without one.
        environment.put("LLM_PROXY_CLAIM", "{\"x-agent-run\":\"live-run\",\"x-agent-epoch\":\"1\"}");
        List<String> command = spec.command();
        if (command.isEmpty()) throw new IllegalArgumentException("The live runner has no command");
        return new GenericContainer<>(DockerImageName.parse(spec.image()))
                // The caller selects a prebuilt image. A missing image fails before a model request.
                .withImagePullPolicy(ignored -> false)
                .withStartupAttempts(1)
                .withAccessToHost(true)
                .withWorkingDirectory(SandboxLayout.WORKSPACE_ROOT)
                .withFileSystemBind(
                        workspace.toAbsolutePath().toString(), SandboxLayout.WORKSPACE_ROOT, BindMode.READ_WRITE)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=512m"))
                .withEnv(environment)
                .withCreateContainerCmdModifier(created -> {
                    created.withEntrypoint(command.getFirst()).withUser(user);
                    Objects.requireNonNull(created.getHostConfig())
                            .withReadonlyRootfs(true)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges:true"))
                            .withMemory(1024L * 1024 * 1024)
                            .withPidsLimit(512L);
                })
                .withCommand(command.subList(1, command.size()).toArray(String[]::new))
                .withStartupCheckStrategy(
                        new StartupCheckStrategy() {
                            @Override
                            public StartupStatus checkStartupState(DockerClient docker, String id) {
                                // Completion is distinct from a successful review: the caller checks its actual exit
                                // code.
                                return DockerStatus.isContainerStopped(getCurrentState(docker, id))
                                        ? StartupStatus.SUCCESSFUL
                                        : StartupStatus.NOT_YET_KNOWN;
                            }
                        }.withTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)));
    }

    static int run(GenericContainer<?> runner, @Nullable Path log) throws IOException {
        var captured = new ConcurrentLinkedQueue<String>();
        runner.withLogConsumer(frame -> {
            captured.add(frame.getUtf8String());
        });
        try {
            runner.start();
            writeLog(log, runner.getLogs());
            return Math.toIntExact(Objects.requireNonNull(
                    runner.getCurrentContainerInfo().getState().getExitCodeLong(),
                    "A completed runner has no exit code"));
        } catch (RuntimeException | IOException failed) {
            // Testcontainers can remove a failed startup's container before the caller's close; keep its streamed log.
            try {
                writeLog(log, String.join("", captured));
            } catch (IOException logFailure) {
                failed.addSuppressed(logFailure);
            }
            throw failed;
        }
    }

    private static void writeLog(@Nullable Path log, String output) throws IOException {
        if (log == null) LOGGER.info("[practice-runner] {}", output);
        else Files.writeString(log, output);
    }

    /** Keeps the proxy's path and port while replacing the host with Testcontainers' scoped listener tunnel. */
    static URI proxyInContainer(String proxyUrl) {
        URI proxy = URI.create(proxyUrl);
        String host = proxy.getHost();
        if (!"http".equals(proxy.getScheme())
                || proxy.getUserInfo() != null
                || proxy.getFragment() != null
                || host == null
                || !List.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host)
                || proxy.getPort() < 1
                || proxy.getPort() > 65_535) {
            throw new IllegalArgumentException("The live runner requires a loopback proxy with an explicit port");
        }
        return URI.create("http://host.testcontainers.internal:" + proxy.getPort() + proxy.getRawPath()
                + (proxy.getRawQuery() == null ? "" : "?" + proxy.getRawQuery()));
    }

    /** Match the owned bind mount's non-root identity, so result files stay readable and removable by the test. */
    static String workspaceUser(Path workspace) throws IOException {
        int uid = (Integer) Files.getAttribute(workspace, "unix:uid");
        int gid = (Integer) Files.getAttribute(workspace, "unix:gid");
        if (uid <= 0 || gid <= 0) {
            throw new IllegalArgumentException("The live runner workspace must have a non-root UID and GID");
        }
        return uid + ":" + gid;
    }

    private static String git(Path workspace, String... arguments) throws IOException, InterruptedException {
        List<String> identity = List.of(
                "git",
                "-c",
                "user.name=Example Developer",
                "-c",
                "user.email=developer@example.org",
                "-c",
                "commit.gpgsign=false");
        String[] command = new String[identity.size() + arguments.length];
        System.arraycopy(identity.toArray(String[]::new), 0, command, 0, identity.size());
        System.arraycopy(arguments, 0, command, identity.size(), arguments.length);
        return fixtureCommand(workspace, command);
    }

    private static void link(Path link, Path target) throws IOException {
        if (Files.exists(link) || Files.isSymbolicLink(link)) {
            Files.delete(link);
        }
        Files.createSymbolicLink(link, target);
    }
}
