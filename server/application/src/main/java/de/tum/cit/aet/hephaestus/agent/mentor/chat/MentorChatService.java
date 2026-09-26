package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextKeys;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentRequest;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorPiAdapter;
import de.tum.cit.aet.hephaestus.agent.mentor.SessionRestore;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.ClientDisconnectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRunnerException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorStreamLostException;
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
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final String REPLY_NOT_SAVED = "Heph's reply couldn't be saved. Please try again.";

    private final UserRepository userRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final MentorPiAdapter mentorPiAdapter;
    // Non-worker roles still expose the API, but only the worker capability can attach a live sandbox.
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
    private final MemberAiRoutingAdapter memberAiRouting;
    private final MemberAiPreferences memberAiPreferences;

    /** The holder lets a disconnect abort a runner attached after lifecycle callbacks were registered. */
    @Override
    public void start(MentorTurnRequest request, SseEmitter emitter) {
        MentorSseChannel channel = new MentorSseChannel(emitter, objectMapper, runnerTimeoutScheduler.scheduler());
        channel.bindLifecycle();
        AtomicReference<@Nullable MentorRunnerClient> clientHolder = new AtomicReference<>();
        channel.onDisconnect(() -> abortRunner(clientHolder.get(), request.threadId()));

        // Record-started fires here so started/completed balance on the executor-rejected branch.
        metrics.recordStarted();
        ExecutorService executor = turnExecutor.executor();
        Long actorId = CurrentScmIdentityHolder.getUserId().orElse(null);
        String actorLogin = CurrentScmIdentityHolder.getLogin().orElse(null);
        Set<Long> accountActorIds = CurrentScmIdentityHolder.getAccountActorIds();
        try {
            executor.execute(() -> {
                // Spring propagates authentication, not our workspace-specific SCM identity.
                if (actorId != null && actorLogin != null) {
                    CurrentScmIdentityHolder.set(actorId, actorLogin, accountActorIds);
                }
                try {
                    dispatchTurn(request, channel, clientHolder);
                } finally {
                    CurrentScmIdentityHolder.clear();
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Mentor turn rejected by executor (probably shutting down): {}", rejected.getMessage());
            metrics.recordCompleted(MentorChatMetrics.Outcome.REJECTED);
            channel.completeWithError("Mentor service is shutting down — please retry shortly.");
        }
    }

    @Override
    public Optional<MentorRefusal> refusal(long workspaceId, long developerId) {
        try {
            admittedBinding(workspaceId, developerId);
            return Optional.empty();
        } catch (MentorRefusedException refused) {
            return Optional.of(refused.reason());
        }
    }

    /** Non-HTTP turns pin their verified actor on the worker thread because no request identity is available. */
    @Override
    public void run(MentorTurnRequest request, MentorChannel channel, long developerId) {
        User developer = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(developerId)));
        metrics.recordStarted();
        try {
            turnExecutor.executor().execute(() -> {
                CurrentScmIdentityHolder.set(developerId, developer.getLogin(), Set.of(developerId));
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
            if (t instanceof Error) throw (Error) t;
        }
    }

    private MentorChatMetrics.Outcome runTurn(
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder) {
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
        // Admission and budget checks precede persistence; the admitted model determines whose budget applies.
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
        ChatThread thread = persistence.ensureThread(
                request.workspaceId(),
                request.threadId(),
                user,
                CurrentScmIdentityHolder.getAccountActorIds(),
                request.userMessage());
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
        Turn turn = new Turn();
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
            // Pi is single-session: hold the lock from attach through the terminal chunk, so a second turn can
            // neither orphan this one nor pick up a runner this turn is about to discard.
            MentorTurnLock.SandboxKey sandboxKey = new MentorTurnLock.SandboxKey(request.workspaceId(), user.getId());
            try (var ignored = turnLock.acquireSandboxLock(sandboxKey)) {
                boolean poisoning = false;
                try {
                    sandbox = attachSandbox(spec);
                    client = startRunner(sandbox, request, channel, clientHolder, state, cookie, turn, contextInputs);
                    try {
                        client.openThread(request.threadId()).get(10, TimeUnit.SECONDS);
                    } catch (Exception openFailure) {
                        if (sessionRestore == null || state.isStreamBroken()) {
                            throw openFailure;
                        }
                        log.warn(
                                "Mentor session restore failed for thread {}; clearing session_jsonl and retrying once: {}",
                                request.threadId(),
                                openFailure.toString());
                        chatThreadRepository.clearSessionJsonl(thread.getId());
                        discardRunner(client, sandbox);
                        clientHolder.set(null);

                        InteractiveSandboxSpec cleanSpec =
                                mentorPiAdapter.buildSandboxSpec(agentRequest, llmConfig, contextInputs, null);
                        sandbox = attachSandbox(cleanSpec);
                        client = startRunner(
                                sandbox, request, channel, clientHolder, state, cookie, turn, contextInputs);
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
                            if (ex != null) {
                                turn.done.completeExceptionally(ex);
                            }
                        });

                        turn.done.get(
                                MentorRunnerClient.DEFAULT_PROMPT_TIMEOUT.toMillis() + 30_000, TimeUnit.MILLISECONDS);
                    } finally {
                        proxyCredentialRegistry.unbindTurn(sandboxSessionId, proxyMeter);
                    }
                } catch (Exception failure) {
                    poisoning = isPoisoning(failure);
                    throw failure;
                } finally {
                    // A runner with a broken stream may still be generating and cannot confirm an abort; a
                    // poisoned one has corrupt state. Either way no later turn may reuse it.
                    if (sandbox != null && (poisoning || state.isStreamBroken())) {
                        discardRunner(client, sandbox);
                    }
                }
            }
            outcome = turn.terminal.get() == Terminal.FAILED_IN_STREAM
                    ? MentorChatMetrics.Outcome.ERROR
                    : MentorChatMetrics.Outcome.SUCCESS;
        } catch (TimeoutException timeout) {
            // Interrupt persistence here; the runner watchdog reclaims the session after a missing terminal event.
            log.warn(
                    "Mentor turn timed out waiting for agent_end (threadId={}): {}",
                    request.threadId(),
                    timeout.toString());
            failTurn(turn, state, channel, cookie, timeout, "Mentor turn timed out before completion.");
            outcome = MentorChatMetrics.Outcome.TIMEOUT;
        } catch (ClientDisconnectedException disconnect) {
            if (clientHolder.get() == null) {
                // No runner subscription exists to complete the persisted turn.
                failTurn(turn, state, channel, cookie, disconnect, null);
            } else {
                log.info("Mentor client disconnected; runner draining: {}", disconnect.getMessage());
                try {
                    turn.done.get(20, TimeUnit.SECONDS);
                } catch (Exception drainEx) {
                    log.debug("Drain after client disconnect timed out / errored: {}", drainEx.toString());
                    failTurn(turn, state, channel, cookie, disconnect, null);
                }
            }
            outcome = MentorChatMetrics.Outcome.CLIENT_DISCONNECT;
        } catch (Exception e) {
            poisoned = isPoisoning(e);
            log.warn("Mentor turn errored (poisoned={}): {}", poisoned, e.getMessage(), e);
            failTurn(turn, state, channel, cookie, e, userFacingError(e));
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
        }
        return outcome;
    }

    /**
     * One turn's single outcome. Whoever settles {@link #terminal} first owns the terminal chunk and the
     * persisted row. The event handler holds {@link #delivery} while it translates and sends an event, and a
     * failure is recorded under it too, so no chunk reaches the client after the failure it contradicts. The
     * stream-loss callback runs on the sandbox's pump and never takes it.
     */
    private static final class Turn {
        final AtomicReference<@Nullable Terminal> terminal = new AtomicReference<>();
        final ReentrantLock delivery = new ReentrantLock();
        final CompletableFuture<Void> done = new CompletableFuture<>();
    }

    private enum Terminal {
        /** Finish arrived and the row is being completed; nothing has told the client yet. */
        COMPLETING,
        /** The row is durably completed, and only then does the client see Finish. */
        COMPLETED,
        /** The event stream settled a failure and interrupted the row itself. */
        FAILED_IN_STREAM,
        /** The turn failed outside the event stream; the turn thread records it. */
        FAILED
    }

    /** Records a failure decided outside the event stream, unless the stream already settled the turn. */
    private void failTurn(
            Turn turn,
            TranslatorState state,
            MentorChannel channel,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            Throwable cause,
            @Nullable String userError) {
        turn.delivery.lock();
        try {
            if (!turn.terminal.compareAndSet(null, Terminal.FAILED) && turn.terminal.get() != Terminal.FAILED) {
                return;
            }
            // Closing first also stores the block the client was still rendering.
            closeOpenBlocks(state, channel);
            interruptOrLeaveForReaper(cookie, state, cause);
            if (userError != null) {
                channel.completeWithError(userError);
            }
        } finally {
            turn.delivery.unlock();
        }
    }

    private MentorRunnerClient startRunner(
            AttachedSandbox sandbox,
            MentorTurnRequest request,
            MentorChannel channel,
            AtomicReference<@Nullable MentorRunnerClient> clientHolder,
            TranslatorState state,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            Turn turn,
            Map<String, byte[]> contextInputs)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        if (channel.isClientGone()) {
            throw new ClientDisconnectedException("Client disconnected during sandbox attach");
        }

        MentorRunnerClient client = new MentorRunnerClient(
                sandbox,
                objectMapper,
                event -> handleEvent(event, state, channel, cookie, turn),
                // Runs on a sandbox thread, so it must not block: the turn thread records the failure. Only a
                // reply already durably completed makes a later loss harmless; until then the runner is
                // unusable, even if the stream settled the turn.
                () -> {
                    if (turn.terminal.compareAndSet(null, Terminal.FAILED)) {
                        state.markStreamBroken();
                        turn.done.completeExceptionally(new MentorStreamLostException());
                    } else if (turn.terminal.get() != Terminal.COMPLETED) {
                        state.markStreamBroken();
                    }
                },
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
                abortRunner(client, request.threadId());
                throw new ClientDisconnectedException("Client disconnected after runner start");
            }

            JsonNode hello = client.hello().get(20, TimeUnit.SECONDS);
            verifyProtocol(hello);
            handedOff = true;
            return client;
        } finally {
            if (!handedOff) {
                clientHolder.compareAndSet(client, null);
                client.close();
            }
        }
    }

    /** Closes the client before the sandbox, so its own subscription ends deliberately rather than as a loss. */
    private static void discardRunner(@Nullable MentorRunnerClient client, AttachedSandbox sandbox) {
        try {
            if (client != null) {
                client.close();
            }
        } catch (RuntimeException ignored) {
            // Best-effort: the sandbox close below is what matters.
        }
        try {
            sandbox.close(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            log.warn("Failed to close mentor sandbox: {}", e.toString());
        }
    }

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
     * Ask the runner to stop generating tokens because nobody will receive them. The cost of tokens
     * already in flight is still charged (Pi can't unsend the LLM request), but no further
     * generation happens. {@code session.abort()} is documented idempotent — calling it after
     * the turn has naturally completed is harmless.
     */
    private static void abortRunner(@Nullable MentorRunnerClient client, UUID threadId) {
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

    /** Bounds traversal of cyclic or unreasonably deep cause chains. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /** The AI SDK reducer rejects an error chunk that follows an unmatched text-start (vercel/ai #11700). */
    private void closeOpenBlocks(TranslatorState state, MentorChannel channel) {
        try {
            translator.closeOpenBlocks(state).forEach(channel::send);
        } catch (RuntimeException ignored) {
            // Best-effort: the channel may already be gone.
        }
    }

    private static boolean isPoisoning(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < MAX_CAUSE_DEPTH) {
            if (cur instanceof MentorRunnerException mre && mre.poisonsSandbox()) {
                return true;
            }
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
        }
        return false;
    }

    private void handleEvent(
            JsonNode piEvent,
            TranslatorState state,
            MentorChannel channel,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            Turn turn) {
        turn.delivery.lock();
        try {
            if (turn.terminal.get() != null) {
                return;
            }
            for (UIMessageChunk chunk : translator.translate(piEvent, state)) {
                if (chunk instanceof UIMessageChunk.Finish finish) {
                    complete(finish, state, channel, cookie, turn);
                    return;
                }
                if (chunk instanceof UIMessageChunk.Error err) {
                    if (turn.terminal.compareAndSet(null, Terminal.FAILED_IN_STREAM)) {
                        sendTerminal(channel, chunk);
                        interruptOrLeaveForReaper(cookie, state, new IllegalStateException(err.errorText()));
                        turn.done.complete(null);
                    }
                    return;
                }
                channel.send(chunk);
            }
        } catch (ClientDisconnectedException disconnect) {
            // Stop channel writes but keep the runner subscription alive until its terminal chunk
            // settles persistence; disconnection is not a second terminal result.
            log.debug(
                    "SSE send failed inside event handler (clientGone={}): {}",
                    channel.isClientGone(),
                    disconnect.toString());
        } catch (RuntimeException e) {
            log.warn("Event translation/send failed: {}", e.getMessage(), e);
            // Claimed while delivery is still held, so no later event can finish a reply missing this one.
            turn.terminal.compareAndSet(null, Terminal.FAILED);
            turn.done.completeExceptionally(e);
        } finally {
            turn.delivery.unlock();
        }
    }

    /**
     * Commits the completed row before the client hears of it, so a reply shown as finished is always one
     * stored as finished. A row another writer already settled, or a commit that fails, ends the turn with an
     * error instead of Finish.
     */
    private void complete(
            UIMessageChunk.Finish finish,
            TranslatorState state,
            MentorChannel channel,
            MentorTurnPersistence.TurnPersistenceCookie cookie,
            Turn turn) {
        if (!turn.terminal.compareAndSet(null, Terminal.COMPLETING)) {
            return;
        }
        UIMessageChunk.Finish toSend = finish;
        try {
            toSend = persistence.augmentFinishWithCost(finish, state);
        } catch (RuntimeException costEx) {
            // Cost-coverage metrics expose missing prices; avoid a warning on every affected turn.
            log.debug("Cost augmentation failed — sending raw Finish: {}", costEx.toString());
        }
        boolean completed;
        try {
            completed = persistence.complete(cookie, state, toSend);
            if (!completed) {
                log.warn(
                        "Mentor reply {} was already settled by another writer; not reporting it finished",
                        cookie.assistantMessageId());
            }
        } catch (RuntimeException saveFailure) {
            log.warn("Mentor reply {} could not be saved", cookie.assistantMessageId(), saveFailure);
            completed = false;
            interruptOrLeaveForReaper(cookie, state, saveFailure);
        }
        if (!completed) {
            turn.terminal.set(Terminal.FAILED_IN_STREAM);
            sendTerminal(channel, new UIMessageChunk.Error(REPLY_NOT_SAVED));
            turn.done.complete(null);
            return;
        }
        turn.terminal.set(Terminal.COMPLETED);
        sendTerminal(channel, toSend);
        // Slack learns whether its buffered reply was suppressed only as it closes, so delivery is settled after.
        MentorChannel.DeliveryOutcome deliveryOutcome = channel.completeWithDone();
        try {
            persistence.recordDelivery(cookie, state, deliveryOutcome);
        } catch (RuntimeException e) {
            // The linked feedback stays prepared, which the TTL sweep settles; it is never marked delivered unseen.
            log.warn("Could not settle the feedback mentor reply {} linked", cookie.assistantMessageId(), e);
        }
        Double costUsd =
                toSend.messageMetadata() != null ? toSend.messageMetadata().costUsd() : null;
        if (costUsd != null) metrics.recordCostUsd(costUsd);
        turn.done.complete(null);
    }

    /** The turn's outcome is already decided; a row that cannot be written now is settled by the in-flight reaper. */
    private void interruptOrLeaveForReaper(
            MentorTurnPersistence.TurnPersistenceCookie cookie, TranslatorState state, Throwable cause) {
        try {
            persistence.interrupt(cookie, state, cause);
        } catch (RuntimeException e) {
            log.warn("Mentor reply {} left in flight for the reaper", cookie.assistantMessageId(), e);
        }
    }

    /** A client that has gone away must not stop the terminal chunk's row from being recorded. */
    private static void sendTerminal(MentorChannel channel, UIMessageChunk chunk) {
        try {
            channel.send(chunk);
        } catch (ClientDisconnectedException disconnect) {
            log.debug("Terminal chunk not delivered, client gone: {}", disconnect.toString());
        }
    }

    private static void verifyProtocol(JsonNode hello) {
        if (hello == null
                || !hello.has("protocolVersion")
                || hello.get("protocolVersion").asInt(0) != MentorRunnerClient.PROTOCOL_VERSION) {
            // Do not stringify arbitrary runner JSON into logs or user-facing errors.
            JsonNode v = hello != null ? hello.get("protocolVersion") : null;
            String got = v == null ? "missing" : (v.isIntegralNumber() ? Integer.toString(v.asInt()) : "non-integer");
            throw new IllegalStateException("Runner protocol mismatch — expected version "
                    + MentorRunnerClient.PROTOCOL_VERSION + ", got " + got);
        }
        // A protocol-only runner serves canned responses and must never accept user traffic.
        if (hello.path("protocolOnly").asBoolean(false)) {
            throw new IllegalStateException(
                    "Runner started in MENTOR_RUNNER_PROTOCOL_ONLY=1 — refusing to serve traffic");
        }
    }

    /**
     * Only sanitized messages cross the channel boundary; raw exception messages may expose
     * internal ids or upstream errors. The server log retains those details.
     */
    private static String userFacingError(Throwable e) {
        if (e.getCause() instanceof MentorStreamLostException || e instanceof MentorStreamLostException) {
            return PiEventToUiChunkTranslator.REPLY_LOST_IN_TRANSIT;
        }
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
        if (e instanceof MentorRefusedException refused) {
            return refused.reason().userMessage();
        }
        if (e instanceof InteractiveSandboxException) {
            return "I couldn't start the mentor runtime. Please try again in a moment.";
        }
        return "Mentor turn failed unexpectedly.";
    }

    private Map<String, byte[]> buildMentorContext(MentorTurnRequest request, User user, UUID currentUserMessageId) {
        return workspaceContextBuilder.build(new ContextRequest.MentorChatRequest(
                request.workspaceId(), user.getId(), request.threadId(), currentUserMessageId));
    }

    private MentorLlmConfig resolveWorkspaceLlmConfig(long workspaceId) {
        WorkspaceAgentBinding binding = admittedBinding(
                workspaceId, CurrentScmIdentityHolder.getUserId().orElse(null));
        return MentorLlmConfig.fromAdmission(binding, llmAdmissionService.admit(binding));
    }

    /** Mentor admission for web and Slack alike: the member's AI choice, then a model within it. */
    private WorkspaceAgentBinding admittedBinding(long workspaceId, @Nullable Long developerId) {
        return memberAiRouting
                .binding(workspaceId, AgentPurpose.MENTOR, developerId)
                .orElseThrow(() -> new MentorRefusedException(refusalWithoutModel(workspaceId, developerId)));
    }

    private MentorRefusal refusalWithoutModel(long workspaceId, @Nullable Long developerId) {
        MemberAiPreferences.Decision decision = memberAiPreferences.forDeveloper(workspaceId, developerId);
        if (decision.choice() == MemberAiChoice.NO_AI) return MentorRefusal.NO_AI;
        return decision.permitsAi() ? MentorRefusal.UNAVAILABLE : MentorRefusal.CHOICE_REQUIRED;
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
