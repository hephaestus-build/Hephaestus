package de.tum.cit.aet.hephaestus.agent.mentor.host;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnDocuments;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnEvidence;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorSituation;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
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

/**
 * What a turn reads from this worker: its context, and the tools it calls with the turn's credential. A turn's
 * context documents are built once, when its host asks for the context, and the turn sandbox stages that build, so one
 * turn sees one consistent picture. A link tool shows only the developer's own results and problems, and
 * {@code show_feedback} claims the feedback's conversation copy for the attempt. The build is dropped when the turn
 * ends, and in any case once no call of the attempt read it for {@link #UNREAD}: a turn whose host is lost or stopped
 * never ends here. A turn that reads after that gets a new build for the same question.
 */
@Service
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
public class HephTurnContexts {

    /**
     * The situation of a turn that this worker cannot give a context folder, for example when no turn sandbox can start.
     * The turn has no read, bash or load tool, and without this line its instructions still send it to read files.
     */
    static final String NO_CONTEXT_FOLDER =
            "This turn has no context folder: you cannot read files, run commands or load repositories. Answer from"
                    + " this conversation and your other tools, and say plainly what you could not check.";

    static final Duration UNREAD = Duration.ofMinutes(15);

    /** Builds a change may overtake in a row before the call fails. */
    static final int MAX_BUILDS = 3;

    /** How far back, and over how many results, a problem link looks for the result that carries the problem. */
    static final Duration PROBLEM_LOOKBACK = Duration.ofDays(90);

    static final int PROBLEM_RESULTS = 90;

    private final MentorContextPathBuilder paths;
    private final HephSituation situations;
    private final MentorTurnService turns;
    private final MentorEntryService entries;
    private final MentorConversationService conversations;
    private final WorkspaceContextBuilder contextBuilder;
    private final ResultHistoryQuery results;
    private final FeedbackStore feedback;
    private final AccountDirectory accounts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TurnContextFolder folder;
    private final TurnContextGenerations generations;

    /** A build of the turn's documents, and the workspace's context generation it was built under. */
    private record Build(long generation, Map<String, byte[]> documents) {}

    private final Cache<Attempt, Build> inputs = Caffeine.newBuilder()
            .expireAfterAccess(UNREAD)
            .scheduler(Scheduler.systemScheduler())
            .build();

    public HephTurnContexts(
            MentorContextPathBuilder paths,
            HephSituation situations,
            MentorTurnService turns,
            MentorEntryService entries,
            MentorConversationService conversations,
            WorkspaceContextBuilder contextBuilder,
            ResultHistoryQuery results,
            FeedbackStore feedback,
            AccountDirectory accounts,
            ObjectMapper mapper,
            Clock clock,
            TurnContextFolder folder,
            TurnContextGenerations generations) {
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
        this.generations = generations;
    }

    private record Attempt(UUID turnId, int attempt) {
        static Attempt of(TurnAttempt turn) {
            return new Attempt(turn.turnId(), turn.attempt());
        }
    }

    /** The model a host runs a turn with: the one the turn was admitted with. */
    public record Model(
            String apiProtocol,
            String modelId,
            @Nullable Integer contextWindow,
            @Nullable Integer maxOutputTokens,
            @Nullable String reasoningEffort) {}

    /**
     * Everything a host needs to run a turn.
     *
     * @param sessionId the Pi session id, which is the conversation id
     * @param entries the Pi session entries before the turn, rendered by the path builder
     * @param prompt the turn's question as the host passes it to {@code prompt()}
     * @param situation the turn's situation ({@link MentorSituation})
     * @param contextFolder whether this worker serves read, bash and load_context
     */
    public record Context(
            UUID sessionId,
            List<ObjectNode> entries,
            String prompt,
            Model model,
            String situation,
            boolean contextFolder) {}

    public Context context(TurnAttempt turn) {
        ZoneId zone = zone(turn);
        MentorContextPathBuilder.TurnContext path = paths.forTurn(turn.workspaceId(), turn.turnId(), zone);
        MentorTurnAdmission admitted = MentorTurnAdmission.read(
                        turns.require(turn.workspaceId(), turn.turnId()).getAdmission())
                .orElseThrow(
                        () -> new IllegalStateException("Mentor turn " + turn.turnId() + " has no admitted model"));
        Map<String, byte[]> built = current(turn, path.questionEntryId(), zone);
        boolean contextFolder = folder.available();
        String loadable = contextFolder ? folder.situation(turn) : NO_CONTEXT_FOLDER;
        String situation = situations.render(turn, zone) + "\n" + (loadable.isEmpty() ? "" : loadable + "\n")
                + MentorTurnEvidence.receipt(mapper, built);
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
                contextFolder);
    }

    /** The turn attempt's context documents, built once per attempt; the turn sandbox stages them. */
    public Map<String, byte[]> documents(TurnAttempt turn) {
        Build cached = inputs.getIfPresent(Attempt.of(turn));
        if (cached != null && cached.generation() == generations.current(turn.workspaceId())) {
            return cached.documents();
        }
        return current(turn, turns.question(turn.workspaceId(), turn.turnId()).getId(), zone(turn));
    }

    /**
     * A build of the attempt's documents under the workspace's current context generation. A build that a change
     * overtook is dropped and made again: it may hold data that is no longer allowed.
     */
    private Map<String, byte[]> current(TurnAttempt turn, UUID questionEntryId, ZoneId zone) {
        for (int tries = 0; tries < MAX_BUILDS; tries++) {
            long generation = generations.current(turn.workspaceId());
            Map<String, byte[]> built = build(turn, questionEntryId, zone);
            if (generations.current(turn.workspaceId()) == generation) {
                inputs.put(Attempt.of(turn), new Build(generation, built));
                return built;
            }
        }
        throw new IllegalStateException("The context of workspace " + turn.workspaceId() + " keeps changing");
    }

    /**
     * The attempt's documents as its context folder stages them: pretty-printed, so that {@code read} pages them by
     * line, with item documents read on demand from their owning source.
     */
    public TurnDocuments folderDocuments(TurnAttempt turn) {
        return new TurnDocuments() {
            @Override
            public Map<String, byte[]> built() {
                Map<String, byte[]> pretty = new LinkedHashMap<>();
                documents(turn).forEach((path, bytes) -> pretty.put(path, pretty(bytes)));
                return pretty;
            }

            @Override
            public Optional<byte[]> item(String path) {
                return contextBuilder
                        .read(turn.workspaceId(), turn.accountId(), path)
                        .map(item -> mapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsBytes(ContextTimes.local(item, zone(turn))));
            }

            @Override
            public String builtAt() {
                return ContextTimes.local(clock.instant(), zone(turn));
            }
        };
    }

    private byte[] pretty(byte[] document) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(mapper.readTree(document));
        } catch (JacksonException notJson) {
            return document;
        }
    }

    /** Whether the turn may link this result: one of the developer's own, readable for conversational mentoring. */
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
        return feedback.claimConversationCopy(turn.workspaceId(), feedbackId, turn.turnId(), turn.attempt())
                .isPresent();
    }

    /**
     * One hit of {@code search_conversation}, with the conversation it belongs to named as Heph sees it.
     *
     * @param createdAt in the developer's zone, as every time a turn reads ({@link ContextTimes})
     */
    public record SearchHit(
            UUID entryId, String branch, String createdAt, String surface, String kind, String excerpt) {}

    public List<SearchHit> search(TurnAttempt turn, MentorEntryService.SearchQuery query) {
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

    /** Drops what the turn attempt read. */
    public void forget(TurnAttempt turn) {
        inputs.invalidate(Attempt.of(turn));
    }

    private ZoneId zone(TurnAttempt turn) {
        return accounts.timeZone(turn.accountId()).orElse(AccountDirectory.DEFAULT_TIME_ZONE);
    }

    /** The turn's context documents, each time in them in the developer's zone ({@link ContextTimes}). */
    private Map<String, byte[]> build(TurnAttempt turn, UUID questionEntryId, ZoneId zone) {
        Map<String, byte[]> built = contextBuilder.build(
                new MentorContextRequest(turn.workspaceId(), turn.accountId(), turn.conversationId(), questionEntryId));
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
