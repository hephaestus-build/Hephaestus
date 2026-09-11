package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextKeys;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentRequest;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorPiAdapter;
import de.tum.cit.aet.hephaestus.agent.mentor.SessionRestore;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.ClientDisconnectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRunnerException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.TurnAlreadyInFlightException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.PiEventToUiChunkTranslator;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorTurnMeter;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetExhaustedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUnpricedUsageBlockedException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Runs one mentor chat turn: persist → attach sandbox → handshake → translate runner events
 * into {@link UIMessageChunk}s on the SSE stream. {@link #start} returns once the turn is
 * submitted to the virtual-thread executor; all blocking work happens off the request thread.
 */
@Service
@RequiredArgsConstructor
public class MentorChatService implements MentorTurnRunner, MentorChatStarter {

    private static final Logger log = LoggerFactory.getLogger(MentorChatService.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final UserRepository userRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final WorkspaceAgentBindingRepository agentBindingRepository;
    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final MentorPiAdapter mentorPiAdapter;
    // Resolved lazily: the InteractiveSandboxService bean is part of the worker capability
    // (DockerSandboxConfiguration, gated on the worker role). On a non-worker pod the bean is
    // absent and this provider yields none — the controller + persistence still wire so the API
    // surface loads, but attaching a live turn requires a worker. getObject() fails loudly there.
    private final ObjectProvider<InteractiveSandboxService> interactiveSandboxServiceProvider;
    private final PiEventToUiChunkTranslator translator;
    private final MentorTurnLock turnLock;
    private final MentorTurnPersistence persistence;
    private final ObjectMapper objectMapper;
    private final MentorChatExecutorConfig.MentorTurnExecutor turnExecutor;
    private final MentorChatExecutorConfig.MentorRunnerTimeoutScheduler runnerTimeoutScheduler;
    private final MentorChatMetrics metrics;
    private final LlmBudgetService llmBudgetService;
    private final LlmAdmissionService llmAdmissionService;
    private final MentorProxyCredentialRegistry proxyCredentialRegistry;

    /**
     * Submit a turn to the virtual-thread executor and return. {@code clientHolder} lets the
     * SSE disconnect hook abort Pi even when the runner client is attached after bindLifecycle
     * (which fires synchronously) — {@code session.abort()} is documented idempotent.
     */
    @Override
    public void start(MentorTurnRequest request, SseEmitter emitter) {
        MentorSseChannel channel = new MentorSseChannel(emitter, objectMapper, runnerTimeoutScheduler.scheduler());
        channel.bindLifecycle();
        AtomicReference<@Nullable MentorRunnerClient> clientHolder = new AtomicReference<>();
        channel.onDisconnect(() -> abortRunnerOnDisconnect(clientHolder.get(), request.threadId()));

        // Record-started fires here so started/completed balance on the executor-rejected branch.
        metrics.recordStarted();
        ExecutorService executor = turnExecutor.executor();
        try {
            executor.execute(() -> dispatchTurn(request, channel, clientHolder));
        } catch (RejectedExecutionException rejected) {
            log.warn("Mentor turn rejected by executor (probably shutting down): {}", rejected.getMessage());
            metrics.recordCompleted(MentorChatMetrics.Outcome.REJECTED);
            channel.completeWithError("Mentor service is shutting down — please retry shortly.");
        }
    }

    /**
     * Transport-neutral entry for a non-HTTP surface (e.g. a Slack DM). Runs one turn for the developer
     * identified by {@code developerLogin} against the caller-supplied {@link MentorChannel}. The shared turn
     * body resolves the {@code User} from the SCM-identity holder, so — because there is no HTTP security
     * context here — we set that holder on the turn thread and clear it afterwards. Mirrors {@link #start}.
     */
    @Override
    public void run(MentorTurnRequest request, MentorChannel channel, String developerLogin) {
        metrics.recordStarted();
        try {
            turnExecutor.executor().execute(() -> {
                CurrentScmIdentityHolder.set(developerLogin);
                try {
                    dispatchTurn(request, channel, new AtomicReference<>());
                } finally {
                    CurrentScmIdentityHolder.clear();
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Slack mentor turn rejected by executor: {}", rejected.getMessage());
            metrics.recordCompleted(MentorChatMetrics.Outcome.REJECTED);
            channel.completeWithError("Mentor service is shutting down — please retry shortly.");
        }
    }

    private void dispatchTurn(
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder) {
        MentorTurnLock.ThreadKey key = new MentorTurnLock.ThreadKey(request.workspaceId(), request.threadId());
        // Outer catch: anything that escapes the lock helper itself (not the lambda) would leave
        // started/completed metrics unbalanced and the emitter dangling for EMITTER_TIMEOUT_MS.
        try {
            Optional<Boolean> acquired = turnLock.withLockOr409(key, () -> {
                Timer.Sample sample = metrics.startTimer();
                try {
                    MentorChatMetrics.Outcome outcome = runTurn(request, channel, clientHolder);
                    metrics.recordCompleted(outcome);
                    return Boolean.TRUE;
                } catch (TurnAlreadyInFlightException dup) {
                    // WARN (not INFO): DB index trip means the JVM lock missed — pageable signal.
                    log.warn("Mentor turn rejected (DB in-flight index): {}", dup.getMessage());
                    metrics.recordCompleted(MentorChatMetrics.Outcome.IN_FLIGHT_CONFLICT_DB);
                    channel.completeWithConflict();
                    return Boolean.FALSE;
                } catch (RuntimeException e) {
                    log.warn(
                            "Mentor turn failed: workspaceId={}, threadId={}: {}",
                            key.workspaceId(),
                            key.threadId(),
                            e.getMessage(),
                            e);
                    metrics.recordCompleted(MentorChatMetrics.Outcome.ERROR);
                    channel.completeWithError(userFacingError(e));
                    return Boolean.FALSE;
                } finally {
                    metrics.stopTimer(sample);
                }
            });
            if (acquired.isEmpty()) {
                log.info(
                        "Mentor turn rejected (in flight): workspaceId={}, threadId={}",
                        key.workspaceId(),
                        key.threadId());
                metrics.recordCompleted(MentorChatMetrics.Outcome.IN_FLIGHT_CONFLICT_LOCAL);
                channel.completeWithConflict();
            }
        } catch (Throwable t) {
            // Anything short of an Error is logged and swallowed: one turn's failure must not take the
            // dispatch loop down with it.
            log.error(
                    "Mentor dispatchTurn escaped: workspaceId={}, threadId={}: {}",
                    key.workspaceId(),
                    key.threadId(),
                    t.getMessage(),
                    t);
            metrics.recordCompleted(MentorChatMetrics.Outcome.ERROR);
            try {
                channel.completeWithError("Mentor turn failed unexpectedly.");
            } catch (RuntimeException ignored) {
                // Best-effort: the channel may already be closed.
            }
            // Re-throw Error subclasses (OOME, StackOverflowError) — JVM stability over metrics tidy-up.
            if (t instanceof Error) throw (Error) t;
        }
    }

    private MentorChatMetrics.Outcome runTurn(
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder) {
        // Push thread + workspace ids into MDC so every WARN/ERROR in this turn carries the
        // correlation keys. Cleared in `finally` so the v-thread pool doesn't leak context.
        org.slf4j.MDC.put("mentorThreadId", request.threadId().toString());
        org.slf4j.MDC.put("mentorWorkspaceId", Long.toString(request.workspaceId()));
        try {
            return runTurnInternal(request, channel, clientHolder);
        } finally {
            org.slf4j.MDC.remove("mentorThreadId");
            org.slf4j.MDC.remove("mentorWorkspaceId");
        }
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    private MentorChatMetrics.Outcome runTurnInternal(
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder) {
        // Both gates run before ANYTHING persists, so a refused turn leaves no partial rows and never
        // warms a sandbox. Budget runs after admission: which purse applies depends on who pays for
        // the bound model.
        MentorLlmConfig llmConfig = resolveWorkspaceLlmConfig(request.workspaceId());

        FundingSource mentorFunding =
                Objects.requireNonNull(llmConfig.connectionScope(), "Mentor model must have a funding source");
        LlmBudgetBlockReason blockReason =
                llmBudgetService.decide(request.workspaceId()).forFunding(mentorFunding);
        if (blockReason == LlmBudgetBlockReason.EXHAUSTED) {
            metrics.recordBudgetBlocked();
            throw new LlmBudgetExhaustedException(mentorFunding);
        }
        if (blockReason == LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED) {
            metrics.recordBudgetBlocked();
            throw new LlmUnpricedUsageBlockedException(mentorFunding);
        }
        User user = userRepository.getCurrentUserElseThrow();
        ChatThread thread =
                persistence.ensureThread(request.workspaceId(), request.threadId(), user, request.userMessage());
        Optional<byte[]> priorSessionBytes = chatThreadRepository.findSessionJsonl(thread.getId());

        UUID assistantMessageId = UUID.randomUUID();
        // Read model only: it makes this turn's completed calls visible to the budget gate while the
        // turn is still running. Billing comes from the turn's row, which the proxy writes per call.
        MentorTurnMeter proxyMeter = new MentorTurnMeter(assistantMessageId, llmConfig.priceSnapshot());
        MentorTurnPersistence.TurnPersistenceCookie cookie = persistence.persistInFlight(
                thread, request.userMessage(), assistantMessageId, request.clientUserMessageId(), llmConfig);
        TranslatorState state = new TranslatorState(assistantMessageId);
        // Frozen onto the turn so the ledger bills the price the runner actually ran at.
        state.bindConnection(llmConfig.connectionScope(), llmConfig.connectionId());
        state.bindAdmission(llmConfig.upstreamModelId(), llmConfig.priceSnapshot());

        AttachedSandbox sandbox = null;
        MentorRunnerClient client = null;
        CompletableFuture<Void> turnComplete = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean errorChunkSeen = new java.util.concurrent.atomic.AtomicBoolean();
        channel.startKeepAlive();
        boolean poisoned = false;
        MentorChatMetrics.Outcome outcome = MentorChatMetrics.Outcome.ERROR;
        try {
            // Start goes out BEFORE sandbox.attach so the client renders a placeholder during the
            // multi-second cold start; markStarted then suppresses the translator's duplicate Start.
            channel.send(new UIMessageChunk.Start(assistantMessageId, null));
            state.markStarted();
            channel.send(UIMessageChunk.DataMentorStatus.of("warming-up", "container-cold"));

            Map<String, byte[]> contextInputs = buildMentorContext(request, user, cookie.userMessageId());
            MentorAgentRequest agentRequest = new MentorAgentRequest(request.workspaceId(), user.getId());
            SessionRestore sessionRestore = priorSessionBytes
                    .filter(bytes -> bytes.length > 0)
                    .map(bytes -> new SessionRestore(request.threadId(), bytes))
                    .orElse(null);
            InteractiveSandboxSpec spec =
                    mentorPiAdapter.buildSandboxSpec(agentRequest, llmConfig, contextInputs, sessionRestore);
            RunnerHandle runner = startRunner(
                    spec, request, channel, clientHolder, state, cookie, turnComplete, errorChunkSeen, contextInputs);
            sandbox = runner.sandbox();
            client = runner.client();

            // Per-sandbox FIFO: Pi's runtime is single-session, so a second open_thread on the same
            // sandbox unsubscribes — and orphans — a turn already streaming. Serialising the
            // open_thread → terminal-chunk window makes the second turn wait instead.
            MentorTurnLock.SandboxKey sandboxKey = new MentorTurnLock.SandboxKey(request.workspaceId(), user.getId());

            try (var ignored = turnLock.acquireSandboxLock(sandboxKey)) {
                try {
                    client.openThread(request.threadId()).get(10, TimeUnit.SECONDS);
                } catch (Exception openFailure) {
                    if (sessionRestore == null) {
                        throw openFailure;
                    }
                    log.warn(
                            "Mentor session restore failed for thread {}; clearing session_jsonl and retrying once: {}",
                            request.threadId(),
                            openFailure.toString());
                    chatThreadRepository.clearSessionJsonl(thread.getId());
                    closeFailedRestoreRunner(client, sandbox);
                    clientHolder.set(null);

                    InteractiveSandboxSpec cleanSpec =
                            mentorPiAdapter.buildSandboxSpec(agentRequest, llmConfig, contextInputs, null);
                    runner = startRunner(
                            cleanSpec,
                            request,
                            channel,
                            clientHolder,
                            state,
                            cookie,
                            turnComplete,
                            errorChunkSeen,
                            contextInputs);
                    sandbox = runner.sandbox();
                    client = runner.client();
                    client.openThread(request.threadId()).get(10, TimeUnit.SECONDS);
                }
                // Bind and unbind INSIDE the sandbox lock: that exclusivity is what stops a call being
                // attributed to the wrong turn. A late call outside the window has no row to bill and
                // the proxy refuses it.
                UUID sandboxSessionId = sandbox.identity().sessionId();
                if (!proxyCredentialRegistry.bindTurn(sandboxSessionId, proxyMeter)) {
                    log.warn(
                            "Mentor sandbox session {} has no live proxy credential; this turn has no billing "
                                    + "target, so the proxy will refuse its LLM calls",
                            sandboxSessionId);
                }
                try {
                    var prompt = client.prompt(
                            request.threadId(), MentorTurnPromptFactory.forRunner(request, contextInputs));
                    state.markLlmCallStarted();
                    prompt.whenComplete((result, ex) -> {
                        if (ex != null && !turnComplete.isDone()) {
                            turnComplete.completeExceptionally(ex);
                        }
                    });

                    turnComplete.get(
                            MentorRunnerClient.DEFAULT_PROMPT_TIMEOUT.toMillis() + 30_000, TimeUnit.MILLISECONDS);
                } finally {
                    proxyCredentialRegistry.unbindTurn(sandboxSessionId, proxyMeter);
                }
            }
            // Error chunks interrupt rather than finalise, so they still need a transport terminal here.
            if (errorChunkSeen.get()) {
                channel.completeWithDone();
            }
            outcome = errorChunkSeen.get() ? MentorChatMetrics.Outcome.ERROR : MentorChatMetrics.Outcome.SUCCESS;
        } catch (TimeoutException timeout) {
            // Turn outlasted the prompt deadline (165s) + 30s grace; the future never resolved.
            // Persistence sees an interrupted assistant row; the runner watchdog is what
            // actually reclaims the Pi session.
            log.warn(
                    "Mentor turn timed out waiting for agent_end (threadId={}): {}",
                    request.threadId(),
                    timeout.toString());
            persistence.interrupt(cookie, state, timeout);
            channel.completeWithError("Mentor turn timed out before completion.");
            outcome = MentorChatMetrics.Outcome.TIMEOUT;
        } catch (ClientDisconnectedException disconnect) {
            if (clientHolder.get() == null) {
                // No runner subscription exists to complete the persisted turn.
                persistence.interrupt(cookie, state, disconnect);
            } else {
                log.info("Mentor client disconnected; runner draining: {}", disconnect.getMessage());
                try {
                    turnComplete.get(20, TimeUnit.SECONDS);
                } catch (Exception drainEx) {
                    log.debug("Drain after client disconnect timed out / errored: {}", drainEx.toString());
                    if (!turnComplete.isDone()) {
                        persistence.interrupt(cookie, state, disconnect);
                    }
                }
            }
            outcome = MentorChatMetrics.Outcome.CLIENT_DISCONNECT;
        } catch (Exception e) {
            poisoned = isPoisoning(e);
            log.warn("Mentor turn errored (poisoned={}): {}", poisoned, e.getMessage(), e);
            persistence.interrupt(cookie, state, e);
            channel.completeWithError(userFacingError(e));
            outcome = poisoned ? MentorChatMetrics.Outcome.POISONED : MentorChatMetrics.Outcome.ERROR;
        } finally {
            channel.close();
            // Skip closeThread on client-disconnect: the runner was already aborted via the channel,
            // and this round-trip would chain onto the drain timeout, holding the per-thread lock.
            boolean skipCloseThread = outcome == MentorChatMetrics.Outcome.CLIENT_DISCONNECT;
            if (client != null) {
                if (!skipCloseThread) {
                    try {
                        client.closeThread(request.threadId())
                                .orTimeout(5, TimeUnit.SECONDS)
                                .exceptionally(ex -> null)
                                .join();
                    } catch (RuntimeException ex) {
                        log.debug("close_thread cleanup failed: {}", ex.toString());
                    }
                }
                // Always release the runner-client resources, even on the disconnect path —
                // the client owns timers / subscriptions independent of the thread session.
                client.close();
            }
            if (poisoned && sandbox != null) {
                // Pi state is corrupt — force the registry to drop the sandbox so the next turn
                // starts from a clean container instead of inheriting bad state.
                try {
                    sandbox.close(Duration.ofSeconds(5));
                } catch (RuntimeException ex) {
                    log.warn("Failed to terminate poisoned mentor sandbox: {}", ex.toString());
                }
            }
        }
        return outcome;
    }

    private RunnerHandle startRunner(
            InteractiveSandboxSpec spec,
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder,
            TranslatorState state,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            CompletableFuture<Void> turnComplete,
            java.util.concurrent.atomic.AtomicBoolean errorChunkSeen,
            Map<String, byte[]> contextInputs)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        AttachedSandbox sandbox = attachSandbox(spec);

        if (channel.isClientGone()) {
            throw new ClientDisconnectedException("Client disconnected during sandbox attach");
        }

        MentorRunnerClient client = new MentorRunnerClient(
                sandbox,
                objectMapper,
                event -> handleEvent(event, state, channel, cookie, turnComplete, errorChunkSeen),
                callback -> handleFetchContext(callback, contextInputs),
                runnerTimeoutScheduler.scheduler(),
                // Per-thread event filter: the sandbox is shared by (userId, workspaceId), so
                // a second tab in the same workspace would otherwise see this tab's events.
                request.threadId());
        // Publish before subscribing so a concurrent disconnect can abort the runner.
        clientHolder.set(client);
        boolean handedOff = false;
        try {
            client.start();
            if (channel.isClientGone()) {
                abortRunnerOnDisconnect(client, request.threadId());
                throw new ClientDisconnectedException("Client disconnected after runner start");
            }

            JsonNode hello = client.hello().get(20, TimeUnit.SECONDS);
            verifyProtocol(hello);
            handedOff = true;
            return new RunnerHandle(sandbox, client);
        } catch (Exception failure) {
            if (isPoisoning(failure)) {
                try {
                    sandbox.close(Duration.ofSeconds(5));
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            if (!handedOff) {
                clientHolder.compareAndSet(client, null);
                client.close();
            }
        }
    }

    private static void closeFailedRestoreRunner(MentorRunnerClient client, AttachedSandbox sandbox) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup before retrying with a clean session.
        }
        try {
            sandbox.close(Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // Best-effort cleanup before retrying with a clean session.
        }
    }

    private record RunnerHandle(AttachedSandbox sandbox, MentorRunnerClient client) {}

    private AttachedSandbox attachSandbox(InteractiveSandboxSpec spec) {
        InteractiveSandboxService sandboxService = interactiveSandboxServiceProvider.getObject();
        try {
            return sandboxService.attach(spec);
        } catch (InteractiveSandboxException first) {
            if (!isStillbornContainerAttach(first)) {
                throw first;
            }
            log.warn("Mentor sandbox died during attach; retrying once: {}", first.getMessage());
            return sandboxService.attach(spec);
        }
    }

    private static boolean isStillbornContainerAttach(InteractiveSandboxException e) {
        String message = e.getMessage();
        return message != null && message.contains("container") && message.contains("is not running");
    }

    /**
     * Ask the runner to stop generating tokens because the client is gone. The cost of tokens
     * already in flight is still charged (Pi can't unsend the LLM request), but no further
     * generation happens. {@code session.abort()} is documented idempotent — calling it after
     * the turn has naturally completed is harmless.
     */
    private static void abortRunnerOnDisconnect(@Nullable MentorRunnerClient client, UUID threadId) {
        if (client == null) return;
        try {
            client.abort(threadId)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null)
                    .join();
        } catch (Exception ignored) {
            // Best-effort; the runner watchdog will fire if it really wedged.
        }
    }

    /** Bound the cause-chain walk so a self-cycle (rare but seen in JDK 21 with virtual threads) doesn't infinite-loop. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private static boolean isPoisoning(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < MAX_CAUSE_DEPTH) {
            if (cur instanceof MentorRunnerException mre && mre.poisonsSandbox()) {
                return true;
            }
            Throwable next = cur.getCause();
            if (next == cur) break; // self-cycle guard
            cur = next;
        }
        return false;
    }

    private void handleEvent(
            JsonNode piEvent,
            TranslatorState state,
            MentorChannel channel,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            CompletableFuture<Void> turnComplete,
            java.util.concurrent.atomic.AtomicBoolean errorChunkSeen) {
        try {
            List<UIMessageChunk> chunks = translator.translate(piEvent, state);
            for (UIMessageChunk chunk : chunks) {
                // Defensive: once the turn is done, drop any trailing chunks. Pi shouldn't emit
                // anything past agent_end, but a misbehaving runner / late delivery would
                // otherwise hit a closed emitter and re-trigger finalise on an already-finalised row.
                if (turnComplete.isDone()) break;
                if (chunk instanceof UIMessageChunk.Finish finish) {
                    UIMessageChunk.Finish toSend = finish;
                    try {
                        toSend = persistence.augmentFinishWithCost(finish, state);
                    } catch (RuntimeException costEx) {
                        // DEBUG, not WARN: a missing price row is observable via the
                        // `mentor_cost_recorded_ratio` gauge already; per-turn WARN under
                        // sustained pricing-table drift would noise out actionable logs.
                        log.debug("Cost augmentation failed — sending raw Finish: {}", costEx.toString());
                    }
                    channel.send(toSend);
                    // Slack discovers suppression only when its final buffer flushes; persist that observed outcome.
                    MentorChannel.DeliveryOutcome deliveryOutcome = channel.completeWithDone();
                    persistence.finalise(cookie, state, toSend, deliveryOutcome);
                    Double costUsd = toSend.messageMetadata() != null
                            ? toSend.messageMetadata().costUsd()
                            : null;
                    if (costUsd != null) metrics.recordCostUsd(costUsd);
                    turnComplete.complete(null);
                } else if (chunk instanceof UIMessageChunk.Error err) {
                    // Persistence sees `interrupted`; the wire already carries the Error chunk.
                    // Failing the future exceptionally would re-emit a generic Error + [DONE]
                    // (double-error on the wire) AND let the outer catch call interrupt again.
                    // Complete normally and let runTurnInternal observe the interrupted row via
                    // the Error-chunk-seen flag below to record the correct outcome metric.
                    channel.send(chunk);
                    persistence.interrupt(cookie, state, new IllegalStateException(err.errorText()));
                    errorChunkSeen.set(true);
                    turnComplete.complete(null);
                } else {
                    channel.send(chunk);
                }
            }
        } catch (ClientDisconnectedException disconnect) {
            // Client gone but the runner subscription stays alive so persistence.finalise runs
            // when Pi emits Finish. Do NOT fail turnComplete — let the natural terminal chunk
            // close it. The outer ClientDisconnectedException catch in runTurn is for the
            // SYNCHRONOUS send paths (Start, DataMentorStatus); inside the runner-event handler
            // we only need to stop writing.
            log.debug(
                    "SSE send failed inside event handler (clientGone={}): {}",
                    channel.isClientGone(),
                    disconnect.toString());
        } catch (RuntimeException e) {
            log.warn("Event translation/send failed: {}", e.getMessage(), e);
            if (!turnComplete.isDone()) {
                turnComplete.completeExceptionally(e);
            }
        }
    }

    private static void verifyProtocol(JsonNode hello) {
        if (hello == null
                || !hello.has("protocolVersion")
                || hello.get("protocolVersion").asInt(0) != MentorRunnerClient.PROTOCOL_VERSION) {
            // Bound the message: a misbehaving runner could ship a 10MB hello frame and
            // hello.get("protocolVersion") might be an unbounded JsonNode whose toString()
            // would bloat the log line and (worse) flow into the user-facing error chunk.
            JsonNode v = hello != null ? hello.get("protocolVersion") : null;
            String got = v == null ? "missing" : (v.isIntegralNumber() ? Integer.toString(v.asInt()) : "non-integer");
            throw new IllegalStateException("Runner protocol mismatch — expected version "
                    + MentorRunnerClient.PROTOCOL_VERSION + ", got " + got);
        }
        // Fail-closed against PROTOCOL_ONLY drift: in stub mode the runner stubs every prompt,
        // so a deploy that accidentally inherits MENTOR_RUNNER_PROTOCOL_ONLY=1 would silently
        // serve canned answers to every user. The runner advertises the flag on hello.
        if (hello.path("protocolOnly").asBoolean(false)) {
            throw new IllegalStateException(
                    "Runner started in MENTOR_RUNNER_PROTOCOL_ONLY=1 — refusing to serve traffic");
        }
    }

    /**
     * Map an unchecked exception to a string the user can see in the chat. The raw
     * {@code e.getMessage()} can leak workspace ids, exception class names, internal stack
     * details ({@code "No LLM config for mentor in workspace 42"},
     * {@code "runner error -32002: <internal>"}); the wire ends up as a chat-error toast in
     * the webapp without any further filtering, so the controller is the right boundary.
     * Raw message stays in the WARN log for ops.
     */
    private static String userFacingError(Throwable e) {
        if (e instanceof LlmBudgetExhaustedException budget) {
            return Objects.requireNonNullElse(budget.getMessage(), "The mentor budget is exhausted.");
        }
        if (e instanceof LlmUnpricedUsageBlockedException unpriced) {
            return Objects.requireNonNullElse(
                    unpriced.getMessage(), "The mentor cannot use an unpriced model under the current budget policy.");
        }
        if (e instanceof MentorRunnerException) {
            return "The mentor hit an unexpected error. Please try again.";
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return "Mentor turn timed out before completion.";
        }
        if (e instanceof ClientDisconnectedException) {
            // Should never surface to a still-connected client, but guard anyway.
            return "Connection lost.";
        }
        if (e instanceof IllegalStateException && isMissingMentorConfig(e.getMessage())) {
            return "Hephaestus is not ready to mentor in this workspace yet. Connect a mentor model, then try again.";
        }
        if (e instanceof InteractiveSandboxException) {
            return "I couldn't start the mentor runtime. Please try again in a moment.";
        }
        return "Mentor turn failed unexpectedly.";
    }

    private static boolean isMissingMentorConfig(@Nullable String message) {
        return (message != null
                && (message.startsWith("No mentor model is configured for workspace ")
                        || message.equals("The configured mentor model is not available")));
    }

    private Map<String, byte[]> buildMentorContext(MentorTurnRequest request, User user, UUID currentUserMessageId) {
        return workspaceContextBuilder.build(new ContextRequest.MentorChatRequest(
                request.workspaceId(), user.getId(), request.threadId(), currentUserMessageId));
    }

    private MentorLlmConfig resolveWorkspaceLlmConfig(long workspaceId) {
        WorkspaceAgentBinding binding = agentBindingRepository
                .findByWorkspaceIdAndPurpose(workspaceId, AgentPurpose.MENTOR)
                .orElseThrow(
                        () -> new IllegalStateException("No mentor model is configured for workspace " + workspaceId));
        if (!binding.isEnabled()) {
            throw new IllegalStateException("The configured mentor model is not available");
        }
        return MentorLlmConfig.fromAdmission(binding, llmAdmissionService.admit(binding));
    }

    private JsonNode handleFetchContext(MentorRunnerClient.FetchContextRequest req, Map<String, byte[]> contextInputs) {
        String path = req.path();
        if (!MentorContextKeys.ALLOWED_OUTPUT_KEYS.contains(path)) {
            throw new IllegalArgumentException("fetch_context path not allowed: " + path);
        }
        byte[] bytes = contextInputs.get(path);
        if (bytes == null) {
            return NODES.nullNode();
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse context JSON for path " + path, e);
        }
    }
}
