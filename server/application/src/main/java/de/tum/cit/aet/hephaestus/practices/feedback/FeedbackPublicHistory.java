package de.tum.cit.aet.hephaestus.practices.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.NoteThreadState;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemIdentityQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackPublicHistory {
    private final ResultViewQuery views;
    private final ResultPresentation presentation;
    private final ReviewRepository reviews;
    private final ObjectMapper mapper;
    private final ResultSourceAuthorization sourceAuthorization;
    private final PracticeRepository practices;
    private final FeedbackCopyRepository copies;
    private final ProblemIdentityQuery problemIdentities;
    private final MirroredThreadStateQuery mirroredThreads;

    public record AuthorizedSupport(UUID resultId, String practiceSlug, HistoryCustody custody) {}

    /** The identity of each of {@code problemIds} that has one, read in one query for a page of the history. */
    public Map<UUID, String> identities(long workspaceId, Collection<UUID> problemIds) {
        return problemIds.isEmpty() ? Map.of() : problemIdentities.identities(workspaceId, problemIds);
    }

    /**
     * The mirrored state of each thread that {@code threadRefs} names on {@code itemIds}, read in one query for a page of
     * the history. A thread that the mirror does not hold is absent: its note's state is {@link NoteThreadState#UNKNOWN}.
     */
    public Map<String, NoteThreadState> threadStates(
            long workspaceId, Collection<Long> itemIds, Collection<String> threadRefs) {
        if (itemIds.isEmpty() || threadRefs.isEmpty()) return Map.of();
        var states = new HashMap<String, NoteThreadState>();
        mirroredThreads
                .resolved(workspaceId, itemIds, threadRefs)
                .forEach(
                        (ref, resolved) -> states.put(ref, resolved ? NoteThreadState.RESOLVED : NoteThreadState.OPEN));
        return states;
    }

    /** The state of a note's thread among {@code states}; null for a copy that is not a line note or file note. */
    static @Nullable NoteThreadState threadState(FeedbackCopy copy, Map<String, NoteThreadState> states) {
        if (!NoteCitations.note(copy)) return null;
        var ref = copy.getThreadRef();
        return ref == null ? NoteThreadState.UNKNOWN : states.getOrDefault(ref, NoteThreadState.UNKNOWN);
    }

    /**
     * The results of the workspace whose custody and source {@code purpose} may use, each with its practice's slug and
     * keyed by its id. They are decided in one read; a refused result, or one whose practice is gone, is absent.
     */
    public Map<UUID, AuthorizedSupport> authorizeAll(
            long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        var checks = sourceAuthorization.checkAll(
                workspaceId, results.stream().map(Result::facts).toList(), purpose);
        var permitted = results.stream()
                .filter(result -> {
                    var check = checks.get(result.getId());
                    return check != null && check.permitted();
                })
                .toList();
        if (permitted.isEmpty()) return Map.of();
        Map<Long, String> slugs = new HashMap<>();
        practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        permitted.stream().map(Result::getPracticeId).distinct().toList())
                .forEach(practice -> slugs.put(practice.getId(), practice.getSlug()));
        Map<UUID, AuthorizedSupport> authorized = new HashMap<>();
        for (var result : permitted) {
            var slug = slugs.get(result.getPracticeId());
            if (slug != null)
                authorized.put(
                        result.getId(),
                        new AuthorizedSupport(
                                result.getId(),
                                slug,
                                Objects.requireNonNull(checks.get(result.getId()))
                                        .custody()));
        }
        return authorized;
    }

    /**
     * What a delivered piece on the work rests on: its results, those that the reader may use, and the identity of each
     * problem that its basis names. Identities and the mirrored state of each note's thread are read once for a page of
     * the history.
     */
    public record PublicSupport(
            List<Result> results,
            List<AuthorizedSupport> authorized,
            Map<UUID, String> identities,
            Map<String, NoteThreadState> threadStates) {
        public PublicSupport {
            results = List.copyOf(results);
            authorized = List.copyOf(authorized);
            identities = Map.copyOf(identities);
            threadStates = Map.copyOf(threadStates);
        }
    }

    /** One delivered piece on the work as the public history shows it. */
    public JsonNode render(
            Feedback row, PublicSupport support, List<FeedbackCopy> confirmed, boolean withdrawn, boolean textAllowed) {
        Currentness freshness = currentness(row.getWorkspaceId(), support.results());
        var byId = support.authorized().stream().collect(Collectors.toMap(AuthorizedSupport::resultId, entry -> entry));
        var review = reviews.findByIdAndWorkspaceId(row.getReviewId(), row.getWorkspaceId())
                .orElseThrow();
        var closed = confirmed.isEmpty()
                ? Set.<UUID>of()
                : Set.copyOf(copies.findClosedAsFixed(
                        row.getWorkspaceId(),
                        confirmed.stream().map(FeedbackCopy::getId).toList()));
        var trace = NoteCitations.trace(mapper, review, row.getId());
        var cites = cites(trace, confirmed, byId, support.identities());
        @Nullable
        String summary =
                !textAllowed || confirmed.stream().noneMatch(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY)
                        ? null
                        : row.getBody();
        var projection = new PublicEntry(
                row.getId(),
                row.getDestination(),
                row.getState(),
                Objects.requireNonNull(row.getDeliveredAt()),
                review.getItemVersion(),
                summary,
                confirmed.stream().anyMatch(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY)
                        ? row.getBody() == null
                        : row.getBody() != null,
                freshness,
                withdrawn,
                closedPractices(trace, confirmed, closed, byId),
                support.results().stream()
                        .map(result -> new PublicBasis(
                                result.getId(),
                                Objects.requireNonNull(byId.get(result.getId())).practiceSlug(),
                                result.getPracticeVersionId() == null
                                        ? null
                                        : result.getPracticeVersionId().toString(),
                                result.getOutcome()))
                        .toList(),
                confirmed.stream()
                        .map(copy -> new PublicCopy(
                                copy.getId(),
                                copy.getKind(),
                                !textAllowed
                                        ? null
                                        : copy.getKind() == FeedbackCopyKind.SUMMARY ? row.getBody() : copy.getBody(),
                                copy.getPath(),
                                copy.getStartLine(),
                                copy.getEndLine(),
                                copy.getSide(),
                                copy.getExternalRef(),
                                copy.getExternalUrl(),
                                Objects.requireNonNull(copy.getPostedAt()),
                                copy.getState(),
                                closed.contains(copy.getId()),
                                threadState(copy, support.threadStates()),
                                cites.getOrDefault(copy.getId(), List.of())))
                        .toList());
        return mapper.valueToTree(projection);
    }

    /** Whether the claim a piece of feedback rests on still holds: its least current supporting result decides. */
    public Currentness currentness(long workspaceId, List<Result> support) {
        if (support.isEmpty()) return Currentness.UNVERIFIABLE;
        var currentness = views
                .views(workspaceId, support.stream().map(Result::facts).toList())
                .stream()
                .map(presentation::currentness)
                .toList();
        return currentness.contains(Currentness.UNVERIFIABLE)
                ? Currentness.UNVERIFIABLE
                : currentness.contains(Currentness.STALE) ? Currentness.STALE : Currentness.CURRENT;
    }

    /**
     * The practices that this piece no longer advises on: it has a note that rests on the practice, and each such note
     * is closed as fixed. A practice that only the summary rests on stays standing. A note rests on the results that
     * its composition support names.
     */
    private List<String> closedPractices(
            @Nullable FeedbackCompositionTrace trace,
            List<FeedbackCopy> confirmed,
            Set<UUID> closed,
            Map<UUID, AuthorizedSupport> byId) {
        if (closed.isEmpty() || trace == null) return List.of();
        var open = new HashSet<String>();
        var closing = new HashSet<String>();
        for (var copy : confirmed) {
            if (copy.getKind() == FeedbackCopyKind.SUMMARY) continue;
            var practices = NoteCitations.support(trace, copy).stream()
                    .map(basis -> byId.get(basis.resultId()))
                    .filter(Objects::nonNull)
                    .map(AuthorizedSupport::practiceSlug)
                    .toList();
            (closed.contains(copy.getId()) ? closing : open).addAll(practices);
        }
        closing.removeAll(open);
        return closing.stream().sorted().toList();
    }

    /**
     * The problems that each note cites, by practice and identity. A problem of a result that the read did not
     * authorize is left out.
     */
    private static Map<UUID, List<NoteCitations.Cite>> cites(
            @Nullable FeedbackCompositionTrace trace,
            List<FeedbackCopy> confirmed,
            Map<UUID, AuthorizedSupport> byId,
            Map<UUID, String> identities) {
        if (trace == null || identities.isEmpty()) return Map.of();
        var practices = new HashMap<UUID, String>();
        byId.forEach((resultId, support) -> practices.put(resultId, support.practiceSlug()));
        var cites = new HashMap<UUID, List<NoteCitations.Cite>>();
        for (var copy : confirmed)
            if (NoteCitations.note(copy))
                cites.put(
                        copy.getId(),
                        List.copyOf(NoteCitations.cites(trace, copy, practices, identities)
                                .keySet()));
        return cites;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicEntry(
            UUID id,
            FeedbackDestination destination,
            FeedbackState state,
            Instant deliveredAt,
            @Nullable String itemVersion,
            @Nullable String body,
            boolean bodyUnavailable,
            Currentness currentness,
            boolean withdrawn,
            // The practices whose every note of this piece Hephaestus resolved as fixed and never reopens (GitLab). The
            // piece no longer stands as their advice; its other notes still do, and a practice only the summary carries
            // stays standing.
            List<String> closedPractices,
            List<PublicBasis> basedOn,
            List<PublicCopy> copies) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicBasis(
            UUID resultId, String practiceSlug, @Nullable String practiceVersionId, ResultOutcome outcome) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicCopy(
            UUID id,
            FeedbackCopyKind kind,
            @Nullable String body,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable CitationSide side,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            Instant postedAt,
            FeedbackCopyState state,
            // Hephaestus resolved this note's thread as fixed, and the provider never reopens it.
            boolean closedAsFixed,
            // The note's thread as the mirror of the provider last recorded it; null for a summary.
            @Nullable NoteThreadState threadState,
            // The problems that this note cites, each with an identity, by practice. A later review on the work finds
            // a repeat of a note that Hephaestus did not close by them.
            List<NoteCitations.Cite> cites) {}
}
