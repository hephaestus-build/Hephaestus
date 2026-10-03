package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRetryRejectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.TurnAlreadyInFlightException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageJobType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.LlmUsageSample;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnLlmUsage;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-turn persistence helper for mentor chat. Uses {@code REQUIRES_NEW} so a turn-internal
 * runtime exception cannot roll back the user/assistant rows.
 */
@Service
public class MentorTurnPersistence {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnPersistence.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConversationalDeliveryReconciler conversationalDeliveryReconciler;
    private final LlmUsageRecorder usageRecorder;

    private final TransactionTemplate requiresNewTx;

    public MentorTurnPersistence(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            WorkspaceRepository workspaceRepository,
            ConversationalDeliveryReconciler conversationalDeliveryReconciler,
            LlmUsageRecorder usageRecorder,
            PlatformTransactionManager transactionManager) {
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.workspaceRepository = workspaceRepository;
        this.conversationalDeliveryReconciler = conversationalDeliveryReconciler;
        this.usageRecorder = usageRecorder;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Finds the account's thread or creates one for {@code user}; a foreign-owner read is hidden as a 404.
     * A thread started through another of the account's actors moves to {@code user}, whose work this turn
     * reads, so the feedback the turn delivers is recorded for the developer it was about.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatThread ensureThread(
            long workspaceId, UUID threadId, User user, Set<Long> accountActorIds, String firstPrompt) {
        return chatThreadRepository
                .findByIdAndWorkspaceId(threadId, workspaceId)
                .map(existing -> {
                    if (existing.getUser() == null
                            || !accountActorIds.contains(existing.getUser().getId())) {
                        throw new EntityNotFoundException("ChatThread", threadId.toString());
                    }
                    existing.setUser(user);
                    return existing;
                })
                .orElseGet(() -> createThread(workspaceId, threadId, user, firstPrompt));
    }

    private ChatThread createThread(long workspaceId, UUID threadId, User user, String firstPrompt) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", String.valueOf(workspaceId)));
        ChatThread thread = new ChatThread();
        thread.setId(threadId);
        thread.setUser(user);
        thread.setWorkspace(workspace);
        thread.setTitle(truncateTitle(firstPrompt));
        return chatThreadRepository.save(thread);
    }

    private static String truncateTitle(String prompt) {
        String s = prompt.strip().replaceAll("\\s+", " ");
        if (s.length() <= 80) return s;
        // Cut on a code-point boundary so a 77th-char surrogate pair (e.g. an emoji) is not split into a lone
        // surrogate before the appended ellipsis.
        int cut = s.offsetByCodePoints(0, Math.min(77, s.codePointCount(0, s.length())));
        return s.substring(0, cut) + "…";
    }

    /**
     * Persist the user message + assistant placeholder in a single transaction, admitted under the
     * thread's lock. The DB unique partial index on {@code (thread_id) WHERE status='in_flight'}
     * turns a second turn into a {@link DataIntegrityViolationException}, which we surface as
     * {@link TurnAlreadyInFlightException}.
     *
     * <p>{@code userMessageId} is the client-supplied UUID, or {@code null} to generate one.
     * Persisting the client's id is what makes a duplicate inbound delivery collapse onto the
     * existing turn instead of starting a second one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TurnPersistenceCookie persistInFlight(
            ChatThread thread,
            String userText,
            UUID assistantMessageId,
            @Nullable UUID userMessageId,
            MentorLlmConfig llmConfig) {
        lockForAdmission(thread);
        try {
            if (userMessageId != null && chatMessageRepository.existsById(userMessageId)) {
                throw new TurnAlreadyInFlightException(
                        thread.getId(), new DataIntegrityViolationException("duplicate client user message id"));
            }
            ChatMessage userMessage = new ChatMessage();
            userMessage.setId(userMessageId != null ? userMessageId : UUID.randomUUID());
            userMessage.setThread(thread);
            userMessage.setRole(ChatMessage.Role.USER);
            userMessage.setStatus(ChatMessage.Status.completed);
            userMessage.setParts(toTextParts(userText));
            // Materialize the parent before its dependent row. Both writes remain in this transaction,
            // including rollback when a concurrent turn wins the unique in-flight constraint.
            ChatMessage savedUser = chatMessageRepository.saveAndFlush(userMessage);
            return persistAssistant(thread, savedUser, assistantMessageId, llmConfig);
        } catch (DataIntegrityViolationException ex) {
            // Spring maps every integrity violation to this one class, so narrow by constraint name:
            // an unrelated CHECK regression must not masquerade as a 409.
            if (isInFlightUniqueViolation(ex) || (userMessageId != null && isDuplicateMessageIdViolation(ex))) {
                throw new TurnAlreadyInFlightException(thread.getId(), ex);
            }
            throw ex;
        }
    }

    /**
     * Admits a new attempt at the reply {@code failedAssistantId}, answering its stored prompt again. Only the latest
     * attempt at the thread's latest prompt, sent as {@code userMessageId}, and only once it was interrupted, may be
     * retried. No USER row is written, and the earlier attempt keeps its outcome and usage.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryAdmission persistRetry(
            ChatThread thread,
            @Nullable UUID userMessageId,
            UUID failedAssistantId,
            UUID assistantMessageId,
            MentorLlmConfig llmConfig) {
        lockForAdmission(thread);
        ChatMessage prompt = userMessageId == null
                ? null
                : chatMessageRepository
                        .findByIdAndThread_Id(userMessageId, thread.getId())
                        .orElse(null);
        ChatMessage failed = chatMessageRepository
                .findByIdAndThread_Id(failedAssistantId, thread.getId())
                .orElse(null);
        if (prompt == null
                || prompt.getRole() != ChatMessage.Role.USER
                || failed == null
                || failed.getRole() != ChatMessage.Role.ASSISTANT
                || !prompt.getId().equals(failed.getParentMessageId())
                || chatMessageRepository.existsByThread_IdAndRoleAndCreatedAtAfter(
                        thread.getId(), ChatMessage.Role.USER, prompt.getCreatedAt())) {
            throw new MentorRetryRejectedException(MentorRetryRejectedException.NOT_RETRYABLE);
        }
        if (chatMessageRepository.existsByThread_IdAndStatus(thread.getId(), ChatMessage.Status.in_flight)) {
            throw new TurnAlreadyInFlightException(
                    thread.getId(), new IllegalStateException("a reply in this thread is still in flight"));
        }
        if (failed.getStatus() != ChatMessage.Status.interrupted
                || chatMessageRepository.existsByParentMessageIdAndRoleAndCreatedAtAfter(
                        prompt.getId(), ChatMessage.Role.ASSISTANT, failed.getCreatedAt())) {
            throw new MentorRetryRejectedException(MentorRetryRejectedException.SUPERSEDED);
        }
        String storedPrompt = storedText(prompt.getParts());
        if (storedPrompt == null) {
            throw new MentorRetryRejectedException(MentorRetryRejectedException.NOT_RETRYABLE);
        }
        try {
            return new RetryAdmission(persistAssistant(thread, prompt, assistantMessageId, llmConfig), storedPrompt);
        } catch (DataIntegrityViolationException ex) {
            if (isInFlightUniqueViolation(ex)) {
                throw new TurnAlreadyInFlightException(thread.getId(), ex);
            }
            throw ex;
        }
    }

    private void lockForAdmission(ChatThread thread) {
        if (chatThreadRepository
                .lockForTurnAdmission(thread.getId(), thread.getWorkspace().getId())
                .isEmpty()) {
            throw new EntityNotFoundException("ChatThread", thread.getId().toString());
        }
    }

    private TurnPersistenceCookie persistAssistant(
            ChatThread thread, ChatMessage prompt, UUID assistantMessageId, MentorLlmConfig llmConfig) {
        ChatMessage assistant = new ChatMessage();
        assistant.setId(assistantMessageId);
        assistant.setThread(thread);
        assistant.setRole(ChatMessage.Role.ASSISTANT);
        assistant.setParentMessage(prompt);
        assistant.setParts(NODES.arrayNode());
        assistant.setStatus(ChatMessage.Status.in_flight);
        assistant.setMetadata(admissionMetadata(llmConfig));
        chatMessageRepository.save(assistant);
        chatMessageRepository.flush();
        if (llmConfig.priceSnapshot() == null) {
            throw new IllegalStateException("Mentor turn has no admitted LLM price snapshot");
        }
        return new TurnPersistenceCookie(
                thread.getId(),
                prompt.getId(),
                assistantMessageId,
                Instant.now(),
                llmConfig.upstreamModelId(),
                llmConfig.priceSnapshot());
    }

    private static ObjectNode admissionMetadata(MentorLlmConfig config) {
        LlmPriceSnapshot price = config.priceSnapshot();
        if (price == null) throw new IllegalStateException("Mentor turn has no admitted LLM price snapshot");
        return MentorAdmissionMetadata.write(config.upstreamModelId(), price);
    }

    /**
     * Match the partial-unique in-flight index by name so a concurrent-turn 409 distinguishes
     * the expected race from other integrity violations.
     */
    private static boolean isInFlightUniqueViolation(DataIntegrityViolationException ex) {
        return DataIntegrityViolationConstraints.hasName(ex, "ux_chat_message_in_flight_v2");
    }

    private static boolean isDuplicateMessageIdViolation(DataIntegrityViolationException ex) {
        return DataIntegrityViolationConstraints.hasName(ex, "chat_message_pkey");
    }

    /**
     * Durably completes the turn: the message and its ledger write share one new transaction. Returns the Finish to
     * send, whose usage and cost are the ones the row and the ledger record; empty, having written nothing, when the
     * row is no longer in flight because another writer (the in-flight reaper, or an interrupt) recorded its outcome
     * first. Any other failure throws.
     */
    public Optional<UIMessageChunk.Finish> complete(
            TurnPersistenceCookie cookie, TranslatorState state, UIMessageChunk.Finish finish) {
        try {
            return Optional.ofNullable(requiresNewTx.execute(tx -> doComplete(cookie, state, finish)));
        } catch (OptimisticLockingFailureException concurrentlyTerminated) {
            return Optional.empty();
        }
    }

    private UIMessageChunk.@Nullable Finish doComplete(
            TurnPersistenceCookie cookie, TranslatorState state, UIMessageChunk.Finish finish) {
        ChatMessage assistant = chatMessageRepository
                .findById(cookie.assistantMessageId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "ChatMessage", cookie.assistantMessageId().toString()));
        if (assistant.getStatus() != ChatMessage.Status.in_flight) {
            return null;
        }
        assistant.setParts(state.partsSnapshot());
        assistant.setStatus(ChatMessage.Status.completed);
        // saveAndFlush, not save: forces the optimistic-lock check inside complete's try/catch instead of
        // at the REQUIRES_NEW commit boundary, where it would escape uncaught. The terminal status also closes
        // the proxy's per-call fence, so the proxy totals read below are final.
        chatMessageRepository.saveAndFlush(assistant);
        TurnUsage turn = turnUsage(assistant.getId(), state);
        UIMessageChunk.Finish recorded = recordedFinish(finish, state, turn);

        // Persisted shape MUST match the wire UIMessageChunk.MessageMetadata: the webapp rehydrates a
        // thread by feeding this GET response into the same typed accessor it uses for live chunks.
        ObjectNode meta = newOrCopyMeta(assistant);
        if (finish.finishReason() != null) {
            meta.put("finishReason", finish.finishReason().wire());
        }
        UIMessageChunk.MessageMetadata metadata = recorded.messageMetadata();
        if (metadata != null) {
            if (metadata.model() != null) {
                meta.put("model", metadata.model());
            }
            UIMessageChunk.MessageMetadata.Usage usage = metadata.usage();
            if (usage != null) {
                ObjectNode usageNode = meta.putObject("usage");
                putIfPresent(usageNode, "input", usage.input());
                putIfPresent(usageNode, "output", usage.output());
                putIfPresent(usageNode, "cacheRead", usage.cacheRead());
                putIfPresent(usageNode, "cacheWrite", usage.cacheWrite());
                putIfPresent(usageNode, "totalTokens", usage.totalTokens());
            }
            if (metadata.costUsd() != null) {
                meta.put("costUsd", metadata.costUsd());
            }
        }
        meta.put(
                "durationMs",
                Duration.between(cookie.startedAt(), Instant.now()).toMillis());
        assistant.setMetadata(meta);
        chatMessageRepository.saveAndFlush(assistant);
        billTurn(assistant, state, cookie, turn);

        byte[] sessionBytes = state.observedSessionJsonl();
        if (sessionBytes != null) {
            chatThreadRepository.updateSessionJsonl(cookie.threadId(), sessionBytes);
        }
        return recorded;
    }

    /**
     * The Finish a turn reports: the usage and cost of {@code turn}, the same account its row and ledger entry
     * record, priced off the turn's admission-frozen {@link LlmPriceSnapshot}. A total the provider reported is kept
     * only when it describes those calls; otherwise it is the sum of every bucket, as Pi counts one.
     */
    UIMessageChunk.Finish recordedFinish(UIMessageChunk.Finish finish, TranslatorState state, TurnUsage turn) {
        UIMessageChunk.MessageMetadata existing = finish.messageMetadata();
        String model = existing != null && existing.model() != null ? existing.model() : state.admittedModel();
        UsageBreakdown usage = turn.usage();
        if (isEmpty(usage)) {
            return new UIMessageChunk.Finish(
                    finish.finishReason(),
                    UIMessageChunk.MessageMetadata.of(
                            model, existing != null && !state.retryAttempted() ? existing.usage() : null, null));
        }
        Long wireTotal = turn.provenance() == UsageProvenance.RUNNER ? wireTotalTokens(finish) : null;
        long total = wireTotal != null
                ? wireTotal
                : usage.inputTokens() + usage.outputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
        var wireUsage = new UIMessageChunk.MessageMetadata.Usage(
                Math.toIntExact(usage.inputTokens()),
                Math.toIntExact(usage.outputTokens()),
                Math.toIntExact(usage.cacheReadTokens()),
                Math.toIntExact(usage.cacheWriteTokens()),
                Math.toIntExact(total));
        return new UIMessageChunk.Finish(
                finish.finishReason(), new UIMessageChunk.MessageMetadata(model, wireUsage, costUsd(state, usage)));
    }

    /** {@code null} when the model is unpriced; cost coverage metrics expose a missing price. */
    private static @Nullable Double costUsd(TranslatorState state, UsageBreakdown usage) {
        LlmPriceSnapshot price = state.admittedPrice();
        if (price == null) {
            return null;
        }
        try {
            var cost = price.calculateCost(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            usage.cacheReadTokens(),
                            usage.cacheWriteTokens())
                    .usd();
            return cost != null ? cost.doubleValue() : null;
        } catch (RuntimeException costEx) {
            log.debug("Cost calculation failed; reporting no cost: {}", costEx.toString());
            return null;
        }
    }

    /**
     * Settles the feedback a completed reply showed, by what its channel reports reached the developer. Runs after
     * the channel closes, because only then is that known, and reads the stored reply, so what settles is what was
     * sent.
     */
    public void recordDelivery(TurnPersistenceCookie cookie, MentorChannel.DeliveryOutcome deliveryOutcome) {
        requiresNewTx.executeWithoutResult(tx -> chatMessageRepository
                .findById(cookie.assistantMessageId())
                .ifPresent(assistant -> reconcileConversationalDelivery(assistant, deliveryOutcome)));
    }

    /**
     * Append this turn's spend to the {@code llm_usage_event} ledger, in the same transaction as the
     * assistant message. Runs for complete AND interrupt: an interrupted turn still burned tokens.
     */
    private void billTurn(ChatMessage assistant, TranslatorState state, TurnPersistenceCookie cookie, TurnUsage turn) {
        ChatThread thread = assistant.getThread();
        if (thread == null || thread.getWorkspace() == null || !state.hasLlmCallStarted()) return;
        UsageBreakdown usage = turn.usage();
        LlmUsageSample sample = new LlmUsageSample(
                LlmUsageJobType.MENTOR_TURN,
                LlmUsageSourceType.MENTOR_TURN,
                assistant.getId(),
                0,
                cookie.upstreamModelId(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                turn.reasoningTokens(),
                Math.max(1, turn.calls()),
                cookie.priceSnapshot(),
                // Neither record had tokens, so the row names no source rather than crediting one that saw
                // nothing — the same distinction the UNVERIFIABLE append below makes about the amount.
                isEmpty(usage) ? UsageProvenance.NONE : turn.provenance(),
                Instant.now());
        if (isEmpty(usage))
            usageRecorder.recordUnverifiable(thread.getWorkspace().getId(), sample);
        else usageRecorder.record(thread.getWorkspace().getId(), sample);
    }

    /**
     * The one account of a turn's calls that its row, its cost and its ledger entry all use. The runner's report
     * and the proxy's per-call meter are two views of the SAME calls, so exactly one is used, never their sum. The
     * runner's is used unless it cannot be complete: when it reported nothing, compacted or retried. Pi reports
     * summary calls as one total and retries as separate continuations; the proxy records every reported call.
     * Read only after the turn's terminal status is flushed.
     */
    private TurnUsage turnUsage(UUID assistantId, TranslatorState state) {
        UsageBreakdown runner = extractUsageFromState(state);
        if (state.compactionAttempted() || state.retryAttempted() || isEmpty(runner)) {
            MentorTurnLlmUsage viaProxy =
                    chatMessageRepository.findLlmUsageById(assistantId).orElse(MentorTurnLlmUsage.NONE);
            if (viaProxy.hasBillableUsage()) {
                return new TurnUsage(
                        new UsageBreakdown(
                                runner.model(),
                                viaProxy.inputTokens(),
                                viaProxy.outputTokens(),
                                viaProxy.cacheReadTokens(),
                                viaProxy.cacheWriteTokens()),
                        viaProxy.totalCalls(),
                        viaProxy.reasoningTokens(),
                        UsageProvenance.PROXY);
            }
            if (state.retryAttempted()) {
                // The final retry report cannot establish the turn's total without its per-call account.
                return new TurnUsage(
                        new UsageBreakdown(runner.model(), 0, 0, 0, 0),
                        state.observedCallCount(),
                        0,
                        UsageProvenance.NONE);
            }
        }
        return new TurnUsage(runner, state.observedCallCount(), 0, UsageProvenance.RUNNER);
    }

    private static boolean isEmpty(UsageBreakdown usage) {
        return (usage.inputTokens() <= 0
                && usage.outputTokens() <= 0
                && usage.cacheReadTokens() <= 0
                && usage.cacheWriteTokens() <= 0);
    }

    private void reconcileConversationalDelivery(ChatMessage assistant, MentorChannel.DeliveryOutcome deliveryOutcome) {
        List<UUID> shownObservationIds = UIMessageChunk.DataObservation.shownObservationIds(assistant.getParts());
        if (shownObservationIds.isEmpty()) {
            return;
        }
        ChatThread thread = assistant.getThread();
        if (thread == null || thread.getWorkspace() == null || thread.getUser() == null) {
            return;
        }
        switch (deliveryOutcome) {
            case INSTANCE_SILENCED ->
                conversationalDeliveryReconciler.suppressForSilentMode(
                        thread.getWorkspace().getId(), thread.getUser().getId(), shownObservationIds);
            case DELIVERED ->
                conversationalDeliveryReconciler.reconcile(
                        thread.getWorkspace().getId(),
                        thread.getUser().getId(),
                        assistant.getId(),
                        shownObservationIds);
            case NOT_DELIVERED -> {}
        }
    }

    /** Records the turn as interrupted unless its row already holds an outcome. */
    public void interrupt(TurnPersistenceCookie cookie, TranslatorState state, Throwable cause) {
        try {
            requiresNewTx.executeWithoutResult(tx -> doInterrupt(cookie, state, cause));
        } catch (OptimisticLockingFailureException stale) {
            // Theirs wins: never downgrade a row another writer already completed.
            log.info(
                    "interrupt lost optimistic-lock race for assistantMessageId={} — leaving prior observation in place",
                    cookie.assistantMessageId());
        }
    }

    private void doInterrupt(TurnPersistenceCookie cookie, TranslatorState state, Throwable cause) {
        chatMessageRepository.findById(cookie.assistantMessageId()).ifPresent(assistant -> {
            if (assistant.getStatus() != ChatMessage.Status.in_flight) {
                return;
            }
            assistant.setParts(state.partsSnapshot());
            assistant.setStatus(ChatMessage.Status.interrupted);
            ObjectNode meta = newOrCopyMeta(assistant);
            meta.put(
                    "error",
                    cause.getMessage() != null
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName());
            meta.put(
                    "durationMs",
                    Duration.between(cookie.startedAt(), Instant.now()).toMillis());
            assistant.setMetadata(meta);
            // saveAndFlush, not save — see doComplete.
            chatMessageRepository.saveAndFlush(assistant);
            billTurn(assistant, state, cookie, turnUsage(assistant.getId(), state));
        });

        // Session bytes the runner shipped before the interrupt still buy prompt-cache continuity.
        byte[] sessionBytes = state.observedSessionJsonl();
        if (sessionBytes != null) {
            chatThreadRepository.updateSessionJsonl(cookie.threadId(), sessionBytes);
        }
    }

    /** The provider-reported {@code totalTokens} carried on the wire Finish, or {@code null} when absent. */
    @Nullable
    private static Long wireTotalTokens(UIMessageChunk.Finish finish) {
        UIMessageChunk.MessageMetadata meta = finish.messageMetadata();
        UIMessageChunk.MessageMetadata.Usage usage = meta != null ? meta.usage() : null;
        Integer total = usage != null ? usage.totalTokens() : null;
        return total != null ? total.longValue() : null;
    }

    private static void putIfPresent(ObjectNode node, String field, @Nullable Integer value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static ObjectNode newOrCopyMeta(ChatMessage message) {
        JsonNode existing = message.getMetadata();
        if (existing != null && existing.isObject()) {
            return ((ObjectNode) existing).deepCopy();
        }
        return NODES.objectNode();
    }

    private static @Nullable String storedText(@Nullable JsonNode parts) {
        if (parts == null || !parts.isArray()) return null;
        for (JsonNode part : parts) {
            if ("text".equals(part.path("type").asString(""))
                    && part.path("text").isString()) {
                String text = part.path("text").asString();
                if (!text.isBlank()) return text;
            }
        }
        return null;
    }

    private static JsonNode toTextParts(String userText) {
        ObjectNode part = NODES.objectNode();
        part.put("type", "text");
        part.put("text", userText);
        return NODES.arrayNode().add(part);
    }

    /** Pull tokens + model from the translator's accumulated usage snapshot. */
    private static UsageBreakdown extractUsageFromState(TranslatorState state) {
        String model = state.admittedModel() != null ? state.admittedModel() : state.observedModel();
        JsonNode usage = state.observedUsage();
        if (usage == null) return new UsageBreakdown(model, 0, 0, 0, 0);
        return new UsageBreakdown(
                model,
                readLong(usage, "input"),
                readLong(usage, "output"),
                readLong(usage, "cacheRead"),
                readLong(usage, "cacheWrite"));
    }

    private static long readLong(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isIntegralNumber() || v.isFloatingPointNumber() ? v.asLong() : 0L;
    }

    public record RetryAdmission(TurnPersistenceCookie cookie, String prompt) {}

    /** Tracking record carried through the turn pipeline. */
    public record TurnPersistenceCookie(
            UUID threadId,
            UUID userMessageId,
            UUID assistantMessageId,
            Instant startedAt,
            String upstreamModelId,
            LlmPriceSnapshot priceSnapshot) {}

    record UsageBreakdown(
            @Nullable String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {}

    record TurnUsage(UsageBreakdown usage, int calls, long reasoningTokens, UsageProvenance provenance) {}
}
