package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
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

    /** Finds or creates {@code user}'s thread; a foreign-owner read is hidden as a 404. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatThread ensureThread(long workspaceId, UUID threadId, User user, String firstPrompt) {
        return chatThreadRepository
                .findByIdAndWorkspaceId(threadId, workspaceId)
                .map(existing -> {
                    if (existing.getUser() == null
                            || !existing.getUser().getId().equals(user.getId())) {
                        throw new EntityNotFoundException("ChatThread", threadId.toString());
                    }
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
     * Persist the user message + assistant placeholder in a single transaction. The DB unique
     * partial index on {@code (thread_id) WHERE status='in_flight'} converts a racy second
     * insert from a non-affinity replica into a {@link DataIntegrityViolationException}, which
     * we surface as {@link TurnAlreadyInFlightException}.
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

            ChatMessage assistant = new ChatMessage();
            assistant.setId(assistantMessageId);
            assistant.setThread(thread);
            assistant.setRole(ChatMessage.Role.ASSISTANT);
            assistant.setParentMessage(savedUser);
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
                    savedUser.getId(),
                    assistantMessageId,
                    Instant.now(),
                    llmConfig.upstreamModelId(),
                    llmConfig.priceSnapshot());
        } catch (DataIntegrityViolationException ex) {
            // Spring maps every integrity violation to this one class, so narrow by constraint name:
            // an unrelated CHECK regression must not masquerade as a 409.
            if (isInFlightUniqueViolation(ex) || (userMessageId != null && isDuplicateMessageIdViolation(ex))) {
                throw new TurnAlreadyInFlightException(thread.getId(), ex);
            }
            throw ex;
        }
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
     * Injects the turn's cost into {@code finish}. Called before the Finish chunk goes on the wire, so
     * the client and {@code chat_message.metadata} show the same number.
     */
    public UIMessageChunk.Finish augmentFinishWithCost(UIMessageChunk.Finish finish, TranslatorState state) {
        Double cost = computeFinalCostUsd(state);
        if (cost == null) return finish;
        UIMessageChunk.MessageMetadata existing = finish.messageMetadata();
        UIMessageChunk.MessageMetadata.Usage usage = existing != null ? existing.usage() : null;
        String model = existing != null ? existing.model() : state.admittedModel();
        return new UIMessageChunk.Finish(finish.finishReason(), new UIMessageChunk.MessageMetadata(model, usage, cost));
    }

    /**
     * Display-only cost for the chat UI, priced off the turn's admission-frozen
     * {@link LlmPriceSnapshot} — the same rates {@link #billTurn} bills from. {@code null} when the
     * model is unpriced or no tokens were observed.
     */
    @Nullable
    private Double computeFinalCostUsd(TranslatorState state) {
        UsageBreakdown breakdown = extractUsageFromState(state);
        LlmPriceSnapshot price = state.admittedPrice();
        if (price == null || isEmpty(breakdown)) {
            return null;
        }
        var cost = price.calculateCost(
                        breakdown.inputTokens(),
                        breakdown.outputTokens(),
                        breakdown.cacheReadTokens(),
                        breakdown.cacheWriteTokens())
                .usd();
        return cost != null ? cost.doubleValue() : null;
    }

    /** Uses {@link TransactionTemplate} so the message and ledger write share an explicit new transaction. */
    public void finalise(
            TurnPersistenceCookie cookie,
            TranslatorState state,
            UIMessageChunk.Finish finish,
            MentorChannel.DeliveryOutcome deliveryOutcome) {
        try {
            requiresNewTx.executeWithoutResult(tx -> doFinalise(cookie, state, finish, deliveryOutcome));
        } catch (OptimisticLockingFailureException stale) {
            // A concurrent writer (typically the in-flight reaper) already recorded a terminal state
            // for this row; theirs wins.
            log.info(
                    "finalise lost optimistic-lock race for assistantMessageId={} — leaving prior observation in place",
                    cookie.assistantMessageId());
        }
    }

    private void doFinalise(
            TurnPersistenceCookie cookie,
            TranslatorState state,
            UIMessageChunk.Finish finish,
            MentorChannel.DeliveryOutcome deliveryOutcome) {
        ChatMessage assistant = chatMessageRepository
                .findById(cookie.assistantMessageId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "ChatMessage", cookie.assistantMessageId().toString()));
        assistant.setParts(state.partsSnapshot());
        assistant.setStatus(ChatMessage.Status.completed);
        ObjectNode meta = newOrCopyMeta(assistant);
        if (finish.finishReason() != null) {
            meta.put("finishReason", finish.finishReason().wire());
        }
        // Persisted shape MUST match the wire UIMessageChunk.MessageMetadata: the webapp rehydrates a
        // thread by feeding this GET response into the same typed accessor it uses for live chunks.
        UsageBreakdown usage = extractUsageFromState(state);
        if (usage.model() != null) {
            meta.put("model", usage.model());
        }
        ObjectNode usageNode = meta.has("usage") && meta.get("usage").isObject()
                ? (ObjectNode) meta.get("usage")
                : meta.putObject("usage");
        usageNode.put("input", usage.inputTokens());
        usageNode.put("output", usage.outputTokens());
        usageNode.put("cacheRead", usage.cacheReadTokens());
        usageNode.put("cacheWrite", usage.cacheWriteTokens());
        // The provider's own total may include cache tokens, so it is not input+output; prefer it.
        Long wireTotalTokens = wireTotalTokens(finish);
        long totalTokens = wireTotalTokens != null ? wireTotalTokens : usage.inputTokens() + usage.outputTokens();
        if (totalTokens > 0) {
            usageNode.put("totalTokens", totalTokens);
        }
        // Reuse the figure already on the wire Finish rather than re-deriving it, so the row holds
        // exactly what the client saw.
        Double wireCostUsd =
                finish.messageMetadata() != null ? finish.messageMetadata().costUsd() : null;
        if (wireCostUsd != null) {
            meta.put("costUsd", wireCostUsd);
        }
        meta.put(
                "durationMs",
                Duration.between(cookie.startedAt(), Instant.now()).toMillis());
        assistant.setMetadata(meta);
        // saveAndFlush, not save: forces the optimistic-lock check inside finalise's try/catch instead of
        // at the REQUIRES_NEW commit boundary, where it would escape uncaught.
        chatMessageRepository.saveAndFlush(assistant);
        billTurn(assistant, state, cookie);
        reconcileConversationalDelivery(assistant, state, deliveryOutcome);

        byte[] sessionBytes = state.observedSessionJsonl();
        if (sessionBytes != null) {
            chatThreadRepository.updateSessionJsonl(cookie.threadId(), sessionBytes);
        }
    }

    /**
     * Append this turn's spend to the {@code llm_usage_event} ledger, in the same transaction as the
     * assistant message. Runs for finalise AND interrupt: an interrupted turn still burned tokens.
     *
     * <p>The runner's own report and the proxy's per-call meter are two views of the SAME calls, so
     * exactly one is billed, never their sum. The proxy's totals are the fallback for a turn that died
     * before the runner reported anything — real calls that were already paid for.
     */
    private void billTurn(ChatMessage assistant, TranslatorState state, TurnPersistenceCookie cookie) {
        ChatThread thread = assistant.getThread();
        if (thread == null || thread.getWorkspace() == null || !state.hasLlmCallStarted()) return;
        UsageBreakdown usage = extractUsageFromState(state);
        int calls = state.observedCallCount();
        long reasoning = 0;
        UsageProvenance provenance = UsageProvenance.RUNNER;
        if (isEmpty(usage)) {
            MentorTurnLlmUsage viaProxy =
                    chatMessageRepository.findLlmUsageById(assistant.getId()).orElse(MentorTurnLlmUsage.NONE);
            if (viaProxy.hasBillableUsage()) {
                log.info(
                        "Mentor turn {} reported no usage of its own; billing the {} call(s) the proxy recorded",
                        assistant.getId(),
                        viaProxy.totalCalls());
                usage = new UsageBreakdown(
                        usage.model(),
                        viaProxy.inputTokens(),
                        viaProxy.outputTokens(),
                        viaProxy.cacheReadTokens(),
                        viaProxy.cacheWriteTokens());
                calls = viaProxy.totalCalls();
                reasoning = viaProxy.reasoningTokens();
                provenance = UsageProvenance.PROXY;
            }
        }
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
                reasoning,
                Math.max(1, calls),
                cookie.priceSnapshot(),
                // Neither record had tokens, so the row names no source rather than crediting one that saw
                // nothing — the same distinction the UNVERIFIABLE append below makes about the amount.
                isEmpty(usage) ? UsageProvenance.NONE : provenance,
                Instant.now());
        if (isEmpty(usage))
            usageRecorder.recordUnverifiable(thread.getWorkspace().getId(), sample);
        else usageRecorder.record(thread.getWorkspace().getId(), sample);
    }

    private static boolean isEmpty(UsageBreakdown usage) {
        return (usage.inputTokens() <= 0
                && usage.outputTokens() <= 0
                && usage.cacheReadTokens() <= 0
                && usage.cacheWriteTokens() <= 0);
    }

    /** Reconcile linked observations in the same transaction as the completed assistant message. */
    private void reconcileConversationalDelivery(
            ChatMessage assistant, TranslatorState state, MentorChannel.DeliveryOutcome deliveryOutcome) {
        List<UUID> linkedObservationIds = state.linkedObservationIds();
        if (linkedObservationIds.isEmpty()) {
            return;
        }
        ChatThread thread = assistant.getThread();
        if (thread == null || thread.getWorkspace() == null || thread.getUser() == null) {
            return;
        }
        switch (deliveryOutcome) {
            case INSTANCE_SILENCED ->
                conversationalDeliveryReconciler.suppressForSilentMode(
                        thread.getWorkspace().getId(), thread.getUser().getId(), linkedObservationIds);
            case DELIVERED ->
                conversationalDeliveryReconciler.reconcile(
                        thread.getWorkspace().getId(),
                        thread.getUser().getId(),
                        assistant.getId(),
                        linkedObservationIds);
            case NOT_DELIVERED -> {}
        }
    }

    /** Transaction shape mirrors {@link #finalise} — see that method's note on the ledger write. */
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
            // saveAndFlush, not save — see doFinalise.
            chatMessageRepository.saveAndFlush(assistant);
            billTurn(assistant, state, cookie);
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

    private static ObjectNode newOrCopyMeta(ChatMessage message) {
        JsonNode existing = message.getMetadata();
        if (existing != null && existing.isObject()) {
            return ((ObjectNode) existing).deepCopy();
        }
        return NODES.objectNode();
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

    /** Tracking record carried through the turn pipeline. */
    public record TurnPersistenceCookie(
            UUID threadId,
            UUID userMessageId,
            UUID assistantMessageId,
            Instant startedAt,
            String upstreamModelId,
            LlmPriceSnapshot priceSnapshot) {}

    private record UsageBreakdown(
            @Nullable String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {}
}
