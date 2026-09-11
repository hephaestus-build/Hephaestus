package de.tum.cit.aet.hephaestus.agent.mentor.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.PiEventToUiChunkTranslator;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end live test for the mentor runner against a real LLM endpoint. This test:
 *
 * <ol>
 *   <li>Ensures the Pi SDK is installed under {@code target/pi-sdk/node_modules} (idempotent;
 *       the install marker survives across test runs and parallel JVMs use a directory lock).</li>
 *   <li>Spawns {@code pi-mentor-runner.ts} directly with {@code node} — no Docker — and points
 *       it at a real OpenAI-compatible endpoint through the production provider contract.</li>
 *   <li>Drives the JSON-RPC protocol the same way {@code MentorRunnerClient} drives it in prod
 *       (hello → open_thread → prompt) and translates every emitted event through the real
 *       {@link PiEventToUiChunkTranslator} so the test exercises the production stream-merge logic.</li>
 * </ol>
 *
 * <p>To exercise the production routing end-to-end, this test uses the real
 * {@link PiRuntimeFactory} to mint the settings.json (defaultProvider=hephaestus) — the same
 * bytes the production agent container would see. The hephaestus custom provider is registered
 * directly on the ModelRuntime by {@code pi-mentor-runner.ts} before {@code createAgentSession},
 * driven by the provider file and proxy env vars seeded from {@link LiveLlmCredentials}. If a
 * future refactor regresses the production path, this live test fails — no test-only extension
 * masks the bug.
 */
@LiveLlmTest
@Tag("live")
class MentorLiveLlmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PI_SDK_VERSION = "0.84.4";

    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(90);

    /** Project-relative location of the SDK install. Build output, never vendored. Gitignored. */
    private static final Path SDK_DIR = Path.of("target", "pi-sdk").toAbsolutePath();

    private static final Path RUNNER =
            Path.of("src", "main", "resources", "agent", "pi-mentor-runner.ts").toAbsolutePath();

    private final List<Path> workspaceDirs = new ArrayList<>();
    private @Nullable Path workspaceDir;
    private @Nullable StdioAttachedSandbox sandbox;

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @BeforeAll
    static void installPiSdk() throws Exception {
        // The marker avoids repeat installs; the sibling lock serializes concurrent JVMs.
        Files.createDirectories(SDK_DIR);
        Path marker = SDK_DIR.resolve(".installed-" + PI_SDK_VERSION);
        if (Files.exists(marker)) {
            return;
        }
        Path lockFile = SDK_DIR.resolve(".install.lock");
        try (var raf = new java.io.RandomAccessFile(lockFile.toFile(), "rw");
                var channel = raf.getChannel();
                var lock = channel.lock()) {
            // Re-check under lock — a parallel JVM may have just finished.
            if (Files.exists(marker)) {
                return;
            }
            // Keep live-test dependencies isolated from the repository install.
            Files.writeString(
                    SDK_DIR.resolve("package.json"),
                    "{\"name\":\"pi-sdk-test-deps\",\"private\":true,\"dependencies\":{\"@earendil-works/pi-coding-agent\":\""
                            + PI_SDK_VERSION
                            + "\"}}");
            ProcessBuilder pb = new ProcessBuilder(
                    "pnpm",
                    "install",
                    "--dir",
                    SDK_DIR.toString(),
                    "--ignore-workspace",
                    "--ignore-scripts",
                    "--reporter=silent");
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process p = pb.start();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("pnpm install for Pi SDK timed out after 180s");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("pnpm install for Pi SDK failed; see stderr above");
            }
            Files.writeString(marker, "ok\n");
        }
    }

    @AfterEach
    void teardown() {
        if (sandbox != null) {
            sandbox.close(Duration.ofSeconds(5));
            sandbox = null;
        }
        for (Path dir : workspaceDirs) {
            deleteRecursive(dir);
        }
        workspaceDirs.clear();
        workspaceDir = null;
    }

    private StdioAttachedSandbox activeSandbox() {
        StdioAttachedSandbox current = sandbox;
        org.junit.jupiter.api.Assertions.assertNotNull(current);
        return current;
    }

    @Test
    void hero_singleTurnStreamsTextAndFinishesWithUsage() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        workspaceDir = stageWorkspace(creds);

        sandbox = spawnRunner(creds, workspaceDir);
        var driver = new RunnerDriver(sandbox);
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);

        // Translate every event through the real production translator so we test the
        // streaming-merge invariant, not a parallel implementation.
        PiEventToUiChunkTranslator translator = new PiEventToUiChunkTranslator();
        TranslatorState state = new TranslatorState(assistantMessageId);
        List<UIMessageChunk> chunks = new ArrayList<>();
        var translationDone = new CompletableFuture<Void>();
        sandbox.subscribe(frame -> {
            if (!isThreadEvent(frame, threadId)) return;
            JsonNode event = frame.path("params").path("event");
            chunks.addAll(translator.translate(event, state));
            if ("agent_end".equals(event.path("type").asString())) {
                translationDone.complete(null);
            }
        });

        driver.prompt(threadId, "Briefly explain unit testing in one sentence.");
        translationDone.get(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Lifecycle invariants
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0)).as("first chunk is Start").isInstanceOf(UIMessageChunk.Start.class);
        assertThat(chunks.get(chunks.size() - 1)).as("last chunk is Finish").isInstanceOf(UIMessageChunk.Finish.class);

        // Streaming-merge invariant
        // Every TextDelta in the stream must share the same block id as the opening TextStart;
        // a regression here breaks AI SDK's client-side reconciliation (deltas get split into
        // separate parts).
        List<UIMessageChunk.TextDelta> deltas = chunks.stream()
                .filter(UIMessageChunk.TextDelta.class::isInstance)
                .map(UIMessageChunk.TextDelta.class::cast)
                .toList();
        assertThat(deltas)
                .as("at least two text deltas (else merge logic untested)")
                .hasSizeGreaterThanOrEqualTo(2);
        String firstBlockId = deltas.get(0).id();
        assertThat(deltas).allMatch(d -> firstBlockId.equals(d.id()), "all deltas share one block id");

        String concatenated =
                deltas.stream().map(UIMessageChunk.TextDelta::delta).reduce("", String::concat);
        assertThat(concatenated.trim()).as("LLM responded with non-blank text").isNotEmpty();
        // Log a snippet so the test report shows what the model actually said — saves a debug round
        // when the response is unexpected.
        System.out.printf("[hero] LLM response (%d chars): %s%n", concatenated.length(), trim(concatenated, 200));

        // Usage capture
        // Pi pumps {input, output, totalTokens, ...} on every message_end. The translator threads
        // them into the Finish chunk's messageMetadata.usage. If `stream_options.include_usage`
        // ever regresses these go to zero — exactly the failure mode this test exists to catch.
        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) chunks.get(chunks.size() - 1);
        assertThat(finish.messageMetadata()).as("Finish carries metadata").isNotNull();
        UIMessageChunk.MessageMetadata.Usage usage = finish.messageMetadata().usage();
        assertThat(usage).as("usage object present").isNotNull();
        assertThat(usage.input()).as("usage.input ≥ 1").isGreaterThanOrEqualTo(1);
        assertThat(usage.output()).as("usage.output ≥ 1").isGreaterThanOrEqualTo(1);
        System.out.printf(
                "[hero] usage: input=%d output=%d totalTokens=%s model=%s%n",
                usage.input(),
                usage.output(),
                usage.totalTokens(),
                finish.messageMetadata().model());

        // Persistence snapshot
        // partsSnapshot is what lands in chat_message.parts JSONB — a single text part once the
        // turn closes. The text part is only populated on the block-close the translator emits at
        // settlement, so the snapshot may not yet carry it; assert parts is well-formed JSON and,
        // when present, that the buffered text matches.
        var partsSnapshot = state.partsSnapshot();
        assertThat(partsSnapshot.isArray()).isTrue();
        // AI SDK reducer pushes one `step-start` per start-step, then content parts. Find the
        // text part (skip the step-start placeholder) — the assertion is that the rehydrated
        // UIMessage carries the concatenated text exactly.
        JsonNode textPart = null;
        for (JsonNode p : partsSnapshot) {
            if ("text".equals(p.path("type").asString())) {
                textPart = p;
                break;
            }
        }
        if (textPart != null) {
            assertThat(textPart.path("text").asString()).isEqualTo(concatenated);
        }
    }

    @Test
    void multiTurn_secondTurnRecallsFirst() throws Exception {
        // Pin Pi SDK's session-bound agent._state.messages threading: a single long-lived
        // runner must keep both turns' messages in its in-memory state across message_end
        // events. Recall failure means rebind/switchSession or dispatch races dropped the
        // history.
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();
        workspaceDir = stageWorkspace(creds);
        sandbox = spawnRunner(creds, workspaceDir);

        var driver = new RunnerDriver(sandbox);
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);

        // Turn 1: plant a token only the assistant can have seen.
        String t1Text =
                runTurnAndCollect(driver, threadId, "Remember the number forty-two. Reply with exactly: noted.");
        System.out.printf("[multi-turn] turn 1 (%d chars): %s%n", t1Text.length(), trim(t1Text, 200));
        assertThat(t1Text.trim()).as("turn 1 produced a non-empty response").isNotEmpty();

        // Turn 2: ask the LLM to recall the fact. If agent._state.messages was not threaded
        // through turn 1 → turn 2, the LLM has no way to answer this.
        String t2Text = runTurnAndCollect(
                driver, threadId, "What number did I ask you to remember? Reply with only the digits.");
        System.out.printf("[multi-turn] turn 2 (%d chars): %s%n", t2Text.length(), trim(t2Text, 200));
        // Accept either the numeric or spelled-out form so the assertion is model-agnostic.
        String t2Lower = t2Text.toLowerCase();
        assertThat(t2Lower)
                .as("turn 2 must recall the planted number — if this fails, the Pi SDK's session-bound "
                        + "agent._state.messages is not being fed through between turns on the same warm "
                        + "runner and multi-turn conversation is broken")
                .satisfiesAnyOf(
                        s -> assertThat(s).contains("42"), s -> assertThat(s).contains("forty-two"));
    }

    @Test
    @DisplayName("5-turn coherence: each follow-up references the prior turn correctly")
    void multiTurn_fiveTurnsCoherent() throws Exception {
        // Regression guard for multi-turn fragmentation (each follow-up returning a shrinking
        // tail of turn 1). Drive five sequential turns on the SAME warm runner with a question
        // that REQUIRES the LLM to integrate turn N-1's answer into turn N's. If
        // agent._state.messages threading regresses anywhere along the chain, at least one
        // turn's answer becomes incoherent and the assertion catches it.
        //
        // The chain: build a list one item per turn. Turn 5 asks for the full list. This
        // requires the LLM to have seen every prior assistant message AND its own prior
        // answers — exactly the failure mode the screenshot showed.
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();
        workspaceDir = stageWorkspace(creds);
        sandbox = spawnRunner(creds, workspaceDir);

        var driver = new RunnerDriver(sandbox);
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);

        // Turns 1-4: add one fruit per turn, ask for a one-word ack.
        String[] fruits = {"apple", "banana", "cherry", "date"};
        for (int i = 0; i < fruits.length; i++) {
            String resp = runTurnAndCollect(
                    driver,
                    threadId,
                    "Add the fruit '" + fruits[i] + "' to the list. Reply with exactly one word: ok.");
            System.out.printf("[5-turn] turn %d (%s): %s%n", i + 1, fruits[i], trim(resp, 60));
            assertThat(resp.trim())
                    .as("turn %d must produce a non-empty response", i + 1)
                    .isNotEmpty();
        }

        // Turn 5: ask for the full list. Only correct if all four prior user+assistant pairs
        // landed in agent._state.messages.
        String summary = runTurnAndCollect(
                driver,
                threadId,
                "List every fruit I have added so far, in the order I added them. "
                        + "Reply with just the fruit names separated by commas. No commentary.");
        System.out.printf("[5-turn] turn 5 summary (%d chars): %s%n", summary.length(), trim(summary, 200));
        String lower = summary.toLowerCase();
        for (String fruit : fruits) {
            assertThat(lower)
                    .as(
                            "turn 5 must include '%s' — its absence proves a turn earlier in the chain "
                                    + "did not thread through agent._state.messages",
                            fruit)
                    .contains(fruit);
        }
    }

    @Test
    void coldRestart_preservesHistoryViaSessionJsonl() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();

        byte[] bytesA = captureSessionBytesAfterTurn(
                creds, threadId, "Remember: my favorite framework is Spring Boot. Reply with exactly: noted.");
        assertThat(bytesA)
                .as("runner emitted session_persisted before the terminal wire event")
                .isNotNull();

        respawnWithSession(creds, threadId, bytesA);
        String followUp = runTurnAndCollect(
                new RunnerDriver(activeSandbox()),
                threadId,
                "What framework am I using? Reply with only the framework name.");
        assertThat(followUp.toLowerCase())
                .as("Pi SDK rehydrated agent state from injected .sessions/<id>.jsonl")
                .contains("spring");
    }

    @Test
    void toolUse_agentFetchesRecentAuthoredWorkContext() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        workspaceDir = stageWorkspace(creds);

        sandbox = spawnRunner(creds, workspaceDir);
        var driver =
                new RunnerDriver(sandbox).withContext("inputs/context/recent_authored_work.json", MAPPER.readTree("""
                {
                  "user": {"login": "octo", "name": "Octo Example"},
                  "pullRequests": [
                    {"number": 12, "title": "Proposal: Slack mentor onboarding", "url": "https://example.invalid/pr/12", "state": "OPEN", "isDraft": false, "additions": 120, "deletions": 20}
                  ],
                  "issues": []
                }
                """));
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);

        PiEventToUiChunkTranslator translator = new PiEventToUiChunkTranslator();
        TranslatorState state = new TranslatorState(assistantMessageId);
        List<UIMessageChunk> chunks = new ArrayList<>();
        var done = new CompletableFuture<Void>();
        sandbox.subscribe(frame -> {
            if (!isThreadEvent(frame, threadId)) return;
            JsonNode event = frame.path("params").path("event");
            chunks.addAll(translator.translate(event, state));
            if ("agent_end".equals(event.path("type").asString())) {
                done.complete(null);
            }
        });

        driver.prompt(
                threadId,
                "Use fetch_context with path inputs/context/recent_authored_work.json, then tell me the PR number and title. "
                        + "You MUST use fetch_context. Do NOT guess.");
        done.get(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // At least one tool was invoked.
        List<UIMessageChunk.ToolInputStart> toolStarts = chunks.stream()
                .filter(UIMessageChunk.ToolInputStart.class::isInstance)
                .map(UIMessageChunk.ToolInputStart.class::cast)
                .toList();
        assertThat(toolStarts)
                .as("agent must invoke fetch_context to answer the question")
                .isNotEmpty();

        List<String> toolNames =
                toolStarts.stream().map(UIMessageChunk.ToolInputStart::toolName).toList();
        assertThat(toolNames).as("tools used should include fetch_context").contains("fetch_context");
        System.out.printf("[tool-use] tools invoked: %s%n", toolNames);

        // Tool produced output (not error).
        List<UIMessageChunk.ToolOutputAvailable> toolOutputs = chunks.stream()
                .filter(UIMessageChunk.ToolOutputAvailable.class::isInstance)
                .map(UIMessageChunk.ToolOutputAvailable.class::cast)
                .toList();
        assertThat(toolOutputs)
                .as("at least one tool call completed with output")
                .isNotEmpty();

        // Agent's text response should reference the planted content.
        String text = chunks.stream()
                .filter(UIMessageChunk.TextDelta.class::isInstance)
                .map(UIMessageChunk.TextDelta.class::cast)
                .map(UIMessageChunk.TextDelta::delta)
                .reduce("", String::concat);
        System.out.printf("[tool-use] LLM response (%d chars): %s%n", text.length(), trim(text, 300));
        assertThat(text)
                .as("agent must answer from recent_authored_work.json")
                .contains("12")
                .containsIgnoringCase("Slack mentor onboarding");
    }

    @Test
    void coldRestart_sessionJsonlIsByteIdentical() throws Exception {
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        UUID threadId = UUID.randomUUID();

        byte[] bytesA = captureSessionBytesAfterTurn(creds, threadId, "Reply with exactly: alpha.");
        assertThat(bytesA).isNotNull();

        Path sessionFile = respawnWithSession(creds, threadId, bytesA);
        runTurnAndCollect(new RunnerDriver(activeSandbox()), threadId, "Reply with exactly: beta.");

        byte[] bytesAfterB = Files.readAllBytes(sessionFile);
        assertThat(bytesAfterB.length).isGreaterThanOrEqualTo(bytesA.length);
        assertThat(Arrays.copyOfRange(bytesAfterB, 0, bytesA.length))
                .as("Pi SDK must append, not rewrite — prompt-cache prefix must survive")
                .isEqualTo(bytesA);
    }

    /**
     * Spawn a runner, run one turn, return the bytes the runner shipped via {@code session_persisted}.
     * Tears the runner down before returning so the caller can stage a fresh container.
     */
    private byte[] captureSessionBytesAfterTurn(LiveLlmCredentials creds, UUID threadId, String prompt)
            throws Exception {
        workspaceDir = stageWorkspace(creds);
        sandbox = spawnRunner(creds, workspaceDir);
        var driver = new RunnerDriver(sandbox);
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);

        AtomicReference<byte[]> captured = new AtomicReference<>();
        sandbox.subscribe(frame -> {
            if (!isThreadEvent(frame, threadId)) return;
            JsonNode event = frame.path("params").path("event");
            if ("session_persisted".equals(event.path("type").asString())) {
                String jsonl = event.path("jsonl").asString("");
                if (!jsonl.isEmpty()) captured.set(jsonl.getBytes(StandardCharsets.UTF_8));
            }
        });
        runTurnAndCollect(driver, threadId, prompt);
        sandbox.close(Duration.ofSeconds(5));
        sandbox = null;
        byte[] session = captured.get();
        assertThat(session).isNotNull();
        return session;
    }

    /**
     * Stage a second workspace pre-seeded with the captured JSONL and spawn a fresh runner against
     * it (mirrors {@code MentorPiAdapter#buildSandboxSpec} injecting {@code .sessions/<id>.jsonl}).
     * Deletes the prior workspace; {@code workspaceDir} is updated so @AfterEach cleans the new one.
     */
    private Path respawnWithSession(LiveLlmCredentials creds, UUID threadId, byte[] sessionBytes) throws Exception {
        Path nextWorkspace = stageWorkspace(creds);
        Path sessionFile = nextWorkspace.resolve(".sessions").resolve(threadId + ".jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.write(sessionFile, sessionBytes);

        // Keep the old workspace alive: the Pi SDK stores the CWD path in session JSONL, and
        // switchSession validates that the stored path still exists on disk. @AfterEach cleans all.
        workspaceDir = nextWorkspace;

        sandbox = spawnRunner(creds, workspaceDir);
        var driver = new RunnerDriver(sandbox);
        driver.expectRunnerReady();
        driver.helloOk();
        driver.openThread(threadId);
        return sessionFile;
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Drive one full turn against the runner and collect the concatenated text deltas.
     * Used by multi-turn / resume tests to assert on the LLM's actual response text.
     */
    private String runTurnAndCollect(RunnerDriver driver, UUID threadId, String prompt) throws Exception {
        PiEventToUiChunkTranslator translator = new PiEventToUiChunkTranslator();
        TranslatorState state = new TranslatorState(UUID.randomUUID());
        List<UIMessageChunk> chunks = new ArrayList<>();
        var done = new CompletableFuture<Void>();
        // Use a per-turn subscription so collected chunks are scoped to this turn only.
        var unsubscribe = activeSandbox().subscribe(frame -> {
            if (!isThreadEvent(frame, threadId)) return;
            JsonNode event = frame.path("params").path("event");
            chunks.addAll(translator.translate(event, state));
            if ("agent_end".equals(event.path("type").asString())) {
                done.complete(null);
            }
        });
        try {
            driver.prompt(threadId, prompt);
            done.get(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return chunks.stream()
                    .filter(UIMessageChunk.TextDelta.class::isInstance)
                    .map(UIMessageChunk.TextDelta.class::cast)
                    .map(UIMessageChunk.TextDelta::delta)
                    .reduce("", String::concat);
        } finally {
            unsubscribe.dispose();
        }
    }

    // Workspace + process plumbing

    private Path stageWorkspace(LiveLlmCredentials creds) throws IOException {
        Path tmp = Files.createTempDirectory("hephaestus-mentor-live-");
        workspaceDirs.add(tmp);
        Files.createDirectories(tmp.resolve(".sessions"));

        // ESM resolution walks node_modules upward from the *importing* file, not from cwd. The
        // production container handles this by `ln -sf /opt/pi-sdk/node_modules
        // /workspace/node_modules` and bind-mounting the runner under /workspace/.run-pi.ts
        // (see PiRuntimeFactory). We mirror both moves here: symlink node_modules under the
        // workspace, and copy the runner into the workspace so resolution finds the symlink.
        Path nodeModulesLink = tmp.resolve("node_modules");
        Path sdkNodeModules = SDK_DIR.resolve("node_modules");
        Files.createSymbolicLink(nodeModulesLink, sdkNodeModules);
        Files.copy(RUNNER, tmp.resolve("pi-mentor-runner.ts"));
        for (String sidecar : new MentorRunnerProfile().sidecarScripts()) {
            Files.copy(Path.of("src", "main", "resources", "agent", sidecar), tmp.resolve(sidecar));
        }

        // System prompt the runner loads from /workspace/agent/mentor/system.md. Keep it minimal —
        // the live LLM doesn't need the full production prompt to prove the round-trip works.
        Path systemPromptDir = tmp.resolve("agent").resolve("mentor");
        Files.createDirectories(systemPromptDir);
        Files.writeString(
                systemPromptDir.resolve("system.md"), "You are a software engineering mentor. Answer concisely.\n");

        // Use the REAL production PiRuntimeFactory paths so this test fails the moment the
        // factory regresses (e.g. a provider refactor breaking the pi-provider.json contract).
        PiRuntimeFactory factory = new PiRuntimeFactory(MAPPER);
        PiPlanSpec spec = new PiPlanSpec(
                "openai-completions",
                creds.model(),
                null,
                null,
                false,
                "live-test-token",
                // never actually checked — no real proxy sits in front of this test
                true,
                300,
                new MentorRunnerProfile(),
                Map.of(),
                "");
        byte[] settingsBytes = factory.buildPiSettingsJson(spec.upstreamModelId());

        // Pi loads its on-disk settings from `~/.pi/settings.json`; redirect with env vars so we
        // never touch the user's real ~/.pi.
        Path piHome = tmp.resolve(".pi-home");
        Files.createDirectories(piHome);
        Files.write(piHome.resolve("settings.json"), settingsBytes);

        // pi-provider.json — the single non-secret provider spec both runners read via the shared
        // pi-provider.ts helper. Written at the workspace root (mirrors PiRuntimeFactory.build()).
        byte[] providerConfigBytes = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(java.util.Map.of(
                        "apiProtocol",
                        spec.apiProtocol(),
                        "modelId",
                        spec.upstreamModelId(),
                        "supportsReasoning",
                        false));
        Files.write(tmp.resolve("pi-provider.json"), providerConfigBytes);

        // No extension file is written: pi-mentor-runner.ts registers the hephaestus provider
        // directly on the ModelRegistry before createAgentSession (mirrors pi-runner.ts), driven by
        // pi-provider.json + the LLM_PROXY_URL/LLM_PROXY_TOKEN env vars set in spawnRunner.

        return tmp;
    }

    private StdioAttachedSandbox spawnRunner(LiveLlmCredentials creds, Path workspace) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment();
        env.putAll(creds.asProcessEnv()); // OPENAI_API_KEY + OPENAI_BASE_URL (legacy back-compat)
        // The runner reads LLM_PROXY_URL / LLM_PROXY_TOKEN — the same env vars the sandbox adapter sets
        // in production (via NetworkPolicy). No real proxy sits in front of this live test, so these point
        // straight at the upstream gateway and the real credential; the runner cannot tell the difference.
        // Without them the provider registration in pi-provider.ts no-ops and no model resolves.
        env.put("LLM_PROXY_URL", creds.baseUrl());
        env.put("LLM_PROXY_TOKEN", creds.apiKey());
        // Pi looks for settings under PI_CODING_AGENT_DIR; pin it inside our temp dir so the
        // runtime never touches the user's real ~/.pi.
        env.put("PI_CODING_AGENT_DIR", workspace.resolve(".pi-home").toString());
        // Use supported runner overrides for workspace paths.
        env.put("MENTOR_RUNNER_CWD", workspace.toString());
        env.put("MENTOR_RUNNER_SESSIONS_DIR", workspace.resolve(".sessions").toString());
        env.put(
                "MENTOR_RUNNER_SYSTEM_PROMPT_PATH",
                workspace
                        .resolve("agent")
                        .resolve("mentor")
                        .resolve("system.md")
                        .toString());
        pb.directory(workspace.toFile());

        pb.command("node", workspace.resolve("pi-mentor-runner.ts").toString());

        pb.redirectErrorStream(false);
        Process process = pb.start();
        return new StdioAttachedSandbox(UUID.randomUUID(), "live-test-user", "live-test-workspace", process);
    }

    private static boolean isThreadEvent(JsonNode frame, UUID threadId) {
        if (!"event".equals(frame.path("method").asString())) return false;
        String observedThreadId = frame.path("params").path("threadId").asString();
        // runner_ready notification has no threadId — we filter it out here intentionally.
        return threadId.toString().equals(observedThreadId);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // RunnerDriver — same handshake MentorRunnerClient drives in prod

    private static final class RunnerDriver {

        private final AttachedSandbox sandbox;
        private final ConcurrentLinkedQueue<JsonNode> responses = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<JsonNode> readyNotifications = new ConcurrentLinkedQueue<>();
        private final ConcurrentMap<String, JsonNode> contextResponses = new ConcurrentHashMap<>();
        private final AtomicInteger requestIdCounter = new AtomicInteger();

        RunnerDriver(AttachedSandbox sandbox) {
            this.sandbox = sandbox;
            sandbox.subscribe(frame -> {
                if (frame.has("id") && (frame.has("result") || frame.has("error"))) {
                    responses.add(frame);
                } else if ("fetch_context".equals(frame.path("method").asString())) {
                    respondToFetchContext(frame);
                } else if ("event".equals(frame.path("method").asString())
                        && "runner_ready"
                                .equals(frame.path("params")
                                        .path("event")
                                        .path("type")
                                        .asString())) {
                    readyNotifications.add(frame);
                }
            });
        }

        RunnerDriver withContext(String path, JsonNode content) {
            contextResponses.put(path, content);
            return this;
        }

        void expectRunnerReady() {
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> !readyNotifications.isEmpty());
        }

        void helloOk() {
            JsonNode response = call("hello", MAPPER.createObjectNode(), Duration.ofSeconds(10));
            assertThat(response.path("result").path("protocolVersion").asInt(0))
                    .as("hello returns protocolVersion 1")
                    .isEqualTo(1);
        }

        void openThread(UUID threadId) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("threadId", threadId.toString());
            JsonNode response = call("open_thread", params, Duration.ofSeconds(30));
            assertThat(response.path("result").path("threadId").asString())
                    .as("open_thread acks with threadId")
                    .isEqualTo(threadId.toString());
        }

        void prompt(UUID threadId, String text) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("threadId", threadId.toString());
            params.put("text", text);
            JsonNode response = call("prompt", params, Duration.ofSeconds(10));
            assertThat(response.path("result").path("accepted").asBoolean())
                    .as("prompt accepted (turn streams via events)")
                    .isTrue();
        }

        private JsonNode call(String method, JsonNode params, Duration timeout) {
            int id = requestIdCounter.incrementAndGet();
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.set("params", params);
            sandbox.send(request);
            // Drain matching response from the queue (out-of-order responses are not expected for
            // the request methods we call, but we still match by id to be safe).
            JsonNode response = await().atMost(timeout)
                    .pollInterval(Duration.ofMillis(20))
                    .until(
                            () -> {
                                for (JsonNode candidate : responses) {
                                    if (candidate.path("id").asInt(-1) == id) {
                                        responses.remove(candidate);
                                        return candidate;
                                    }
                                }
                                return null;
                            },
                            Objects::nonNull);
            if (response.has("error")) {
                throw new IllegalStateException(
                        "RPC " + method + " failed: " + response.path("error").toString());
            }
            return response;
        }

        private void respondToFetchContext(JsonNode frame) {
            String path = frame.path("params").path("path").asString("");
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", frame.get("id"));
            JsonNode content = contextResponses.get(path);
            if (content == null) {
                ObjectNode error = response.putObject("error");
                error.put("code", -32000);
                error.put("message", "test context missing: " + path);
            } else {
                ObjectNode result = response.putObject("result");
                result.set("content", content);
            }
            sandbox.send(response);
        }
    }
}
