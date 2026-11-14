package de.tum.cit.aet.hephaestus.agent.conversation.host;

import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.ContextFolderPaths;
import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnEvidence;
import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.conversation.ConversationContextPathBuilder;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSituation;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds a turn's context from server-held data and admits its feedback links. */
@Service
@ConditionalOnAgentRuntime
@Profile(ConversationTurnConfiguration.PROFILES)
public class ConversationTurnContexts {

    /** How far back, and over how many results, a problem link looks for the result that carries the problem. */
    static final Duration PROBLEM_LOOKBACK = Duration.ofDays(90);

    static final int PROBLEM_RESULTS = 90;

    private final ConversationContextPathBuilder paths;
    private final TurnSituation situations;
    private final ConversationTurnService turns;
    private final ConversationEntryService entries;
    private final ConversationService conversations;
    private final WorkspaceContextBuilder contextBuilder;
    private final ResultHistoryQuery results;
    private final ConversationClaims feedback;
    private final AccountDirectory accounts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TurnContextLoads folder;

    public ConversationTurnContexts(
            ConversationContextPathBuilder paths,
            TurnSituation situations,
            ConversationTurnService turns,
            ConversationEntryService entries,
            ConversationService conversations,
            WorkspaceContextBuilder contextBuilder,
            ResultHistoryQuery results,
            ConversationClaims feedback,
            AccountDirectory accounts,
            ObjectMapper mapper,
            Clock clock,
            TurnContextLoads folder) {
        this.paths = paths;
        this.situations = situations;
        this.turns = turns;
        this.entries = entries;
        this.conversations = conversations;
        this.contextBuilder = contextBuilder;
        this.results = results;
        this.feedback = feedback;
        this.accounts = accounts;
        this.mapper = mapper;
        this.clock = clock;
        this.folder = folder;
    }

    /** The model a loop runs a turn with: the one the turn was admitted with. */
    public record Model(
            String apiProtocol,
            String modelId,
            @Nullable Integer contextWindow,
            @Nullable Integer maxOutputTokens,
            @Nullable String reasoningEffort) {}

    /** Hephaestus's system prompt: the conversation's instructions and the feedback writing guide, the same for every turn. */
    static final String SYSTEM_PROMPT =
            new String(PiRuntimeFactory.loadClasspathResource("conversation/system.md"), StandardCharsets.UTF_8)
                    + "\n\n" + FeedbackWritingGuide.text();

    /**
     * Everything the loop needs to run a turn.
     *
     * @param sessionId the Pi session id, which is the conversation id
     * @param entries the Pi session entries before the turn, rendered by the path builder
     * @param prompt the turn's question as the loop passes it to {@code prompt()}
     * @param situation the turn's situation ({@link ConversationSituation})
     * @param contextFolder whether the servers serve read, bash and load_context
     * @param systemPrompt Hephaestus's system prompt, which the loop gives the turn's session
     */
    public record Context(
            UUID sessionId,
            List<ObjectNode> entries,
            String prompt,
            Model model,
            String situation,
            boolean contextFolder,
            String systemPrompt,
            String documents) {}

    public Context context(TurnAttempt turn) {
        ZoneId zone = zone(turn);
        ConversationContextPathBuilder.TurnContext path = paths.forTurn(turn.workspaceId(), turn.turnId(), zone);
        ConversationTurnAdmission admitted = ConversationTurnAdmission.read(
                        turns.require(turn.workspaceId(), turn.turnId()).getAdmission())
                .orElseThrow(() ->
                        new IllegalStateException("Conversation turn " + turn.turnId() + " has no admitted model"));
        Map<String, byte[]> built = build(turn, path.questionEntryId(), zone);
        String loadable = folder.situation(turn);
        String situation = situations.render(turn, zone) + "\n" + (loadable.isEmpty() ? "" : loadable + "\n")
                + ConversationTurnEvidence.receipt(mapper, built);
        var effort = admitted.reasoningEffort();
        return new Context(
                path.sessionId(),
                path.entries(),
                path.prompt(),
                new Model(
                        admitted.route().apiProtocol(),
                        admitted.model(),
                        admitted.contextWindow(),
                        admitted.maxOutputTokens(),
                        effort == null ? null : effort.name()),
                situation,
                true,
                SYSTEM_PROMPT,
                Base64.getEncoder()
                        .encodeToString(folder.documentsArchive(
                                turn, prettyDocuments(built), ContextTimes.local(clock.instant(), zone))));
    }

    private Map<String, byte[]> prettyDocuments(Map<String, byte[]> documents) {
        Map<String, byte[]> pretty = new LinkedHashMap<>();
        documents.forEach((path, bytes) -> pretty.put(path, pretty(bytes)));
        return pretty;
    }

    /** Reads an item document from its owning source, with that source's workspace and developer checks. */
    public Optional<byte[]> document(TurnAttempt turn, String path) {
        return ContextFolderPaths.requestedDocument(path)
                .flatMap(relative -> contextBuilder.read(turn.workspaceId(), turn.accountId(), relative))
                .map(item -> mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(ContextTimes.local(item, zone(turn))));
    }

    private byte[] pretty(byte[] document) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(mapper.readTree(document));
        } catch (JacksonException notJson) {
            return document;
        }
    }

    /** Whether the turn may link this result: one of the developer's own, readable in a conversation. */
    public boolean admitsResult(TurnAttempt turn, UUID resultId) {
        return results.find(turn.workspaceId(), resultId, turn.accountId(), SourceUsePurpose.CONVERSATIONAL_MENTORING)
                .filter(entry -> Long.valueOf(turn.accountId()).equals(entry.developerAccountId()))
                .isPresent();
    }

    /** Whether the turn may link this problem. A problem is shown under its result, which decides for both. */
    public boolean admitsProblem(TurnAttempt turn, UUID problemId) {
        return results
                .forDeveloper(
                        turn.workspaceId(), turn.accountId(), clock.instant().minus(PROBLEM_LOOKBACK), PROBLEM_RESULTS)
                .stream()
                .filter(entry -> entry.problems().stream()
                        .anyMatch(problem -> problem.problemId().equals(problemId)))
                .map(ResultHistoryEntry::resultId)
                .findFirst()
                .filter(resultId -> admitsResult(turn, resultId))
                .isPresent();
    }

    /**
     * Claims the conversation copy of the feedback for the turn's attempt, so its words may be shown now. A repeat in
     * the same attempt keeps the claim it holds.
     *
     * @return whether the attempt holds the claim
     */
    public boolean showFeedback(TurnAttempt turn, UUID feedbackId) {
        if (turns.require(turn.workspaceId(), turn.turnId()).getSurface() == ConversationTurn.Surface.SYSTEM)
            return false;
        return feedback.claimConversationCopy(turn.workspaceId(), feedbackId, turn.turnId(), turn.attempt())
                .isPresent();
    }

    /**
     * One hit of {@code search_conversation}, with the conversation it belongs to named as Hephaestus sees it.
     *
     * @param createdAt in the developer's zone, as every time a turn reads ({@link ContextTimes})
     */
    public record SearchHit(
            UUID entryId, String branch, String createdAt, String surface, String kind, String excerpt) {}

    public List<SearchHit> search(TurnAttempt turn, ConversationEntryService.SearchQuery query) {
        UUID main =
                conversations.require(turn.workspaceId(), turn.conversationId()).rootId();
        ZoneId zone = zone(turn);
        return entries.search(turn.workspaceId(), turn.conversationId(), query).stream()
                .map(hit -> new SearchHit(
                        hit.entryId(),
                        hit.conversationId().equals(turn.conversationId())
                                ? "this"
                                : hit.conversationId().equals(main) ? "main" : "thread",
                        ContextTimes.local(hit.createdAt(), zone),
                        hit.surface().name(),
                        hit.kind().name(),
                        hit.excerpt()))
                .toList();
    }

    private ZoneId zone(TurnAttempt turn) {
        return accounts.timeZone(turn.accountId()).orElse(AccountDirectory.DEFAULT_TIME_ZONE);
    }

    /** The turn's context documents, each time in them in the developer's zone ({@link ContextTimes}). */
    private Map<String, byte[]> build(TurnAttempt turn, UUID questionEntryId, ZoneId zone) {
        Map<String, byte[]> built = contextBuilder.build(new ConversationContextRequest(
                turn.workspaceId(), turn.accountId(), turn.conversationId(), questionEntryId));
        Map<String, byte[]> local = new LinkedHashMap<>();
        built.forEach((key, bytes) -> local.put(key, local(bytes, zone)));
        return local;
    }

    private byte[] local(byte[] document, ZoneId zone) {
        try {
            return mapper.writeValueAsBytes(ContextTimes.local(mapper.readTree(document), zone));
        } catch (JacksonException notJson) {
            return document;
        }
    }
}
