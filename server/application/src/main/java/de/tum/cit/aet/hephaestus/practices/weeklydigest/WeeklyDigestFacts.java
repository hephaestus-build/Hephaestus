package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.profile.OverviewWindow;
import de.tum.cit.aet.hephaestus.practices.profile.ProfileChangeQuery;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HephThreadLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the facts of one developer's weekly digest when it is written ({@link #compose}) and every time it is read
 * ({@link #render}), so a digest never shows what can no longer be shown. Deterministic, with no model, and it
 * changes no delivery state. The developer is an account.
 *
 * <p>Feedback is what reached the developer in the week on the work or in conversation
 * ({@link WeeklyDigestRepository#REACHED}). Practice page feedback is only the waiting count. Changes are the Practice
 * profile's own ({@link ProfileChangeQuery}) without the {@code FEEDBACK_*} changes: a later read can lose a change,
 * never gain one.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class WeeklyDigestFacts {

    /** Changes of Practice page feedback; the waiting count covers that feedback. */
    private static final Set<ProfileChangeType> PRACTICE_PAGE_CHANGES = EnumSet.of(
            ProfileChangeType.FEEDBACK_NEW, ProfileChangeType.FEEDBACK_RESOLVED, ProfileChangeType.FEEDBACK_RESET);

    /** By practice group, then by the practice's place in the catalog. */
    private static final Comparator<Row> CATALOG_ORDER = Comparator.<Row, Boolean>comparing(
                    row -> row.practice().getGroup() == null)
            .thenComparingInt(row -> {
                PracticeGroup group = row.practice().getGroup();
                return group == null ? 0 : group.getPosition();
            })
            .thenComparing(row -> {
                PracticeGroup group = row.practice().getGroup();
                return group == null ? "" : group.getName();
            })
            .thenComparingInt(row -> row.practice().getPosition())
            .thenComparing(Row::practiceName)
            .thenComparing(Row::deliveredAt);

    private final WeeklyDigestRepository repository;
    private final FeedbackBasisRepository basisRepository;
    private final PracticePageFeedbackBasis basis;
    private final PracticePageFeedbackService practicePage;
    private final FeedbackVisibilityService visibility;
    private final ProfileChangeQuery profileChanges;
    private final ProductReferenceQuery references;
    private final HephThreadLookup hephThreads;

    /**
     * A digest as it reads now. Heph reads it as one JSON document: the digest's fields, and the results and problems
     * behind each listed piece that still shows and is not withdrawn, for {@code link_result} and {@code link_problem}.
     *
     * @param resultIds by feedback id, the results the piece rests on
     * @param problemIds by feedback id, the problems the piece names
     */
    public record Rendered(
            @JsonUnwrapped WeeklyDigestDTO digest, Map<UUID, List<UUID>> resultIds, Map<UUID, List<UUID>> problemIds) {}

    /**
     * Whether anything happened for the developer in the week: feedback reached them, Practice page feedback was
     * prepared for them, or a review recorded a result about them. A cheap check before {@link #compose}; only
     * {@link #compose} decides.
     */
    @Transactional(readOnly = true)
    public boolean anythingIn(long workspaceId, long accountId, WeeklyDigestWindow window) {
        return repository.anythingBetween(workspaceId, accountId, window.start(), window.end());
    }

    /**
     * What a digest for the week holds, or nothing when the week was quiet. A week is quiet unless feedback that
     * still stands reached the developer in it, something changed on their Practice profile in it, or Practice page
     * feedback prepared in it waits unread: old unread feedback alone does not send a digest every week.
     */
    @Transactional(readOnly = true)
    public Optional<WeeklyDigestPayload> compose(long workspaceId, long accountId, WeeklyDigestWindow window) {
        List<Row> rows =
                rows(workspaceId, repository.findReachedBetween(workspaceId, accountId, window.start(), window.end()));
        List<ProfileChangeDTO> changes = changes(workspaceId, accountId, window);
        OverviewWindow week = window.overview();
        boolean unreadFromTheWeek =
                practicePage.unreadCreatedAt(workspaceId, accountId).stream().anyMatch(week::contains);
        boolean feedbackStands = rows.stream().anyMatch(row -> row.withdrawnAt() == null);
        if (!feedbackStands && changes.isEmpty() && !unreadFromTheWeek) {
            return Optional.empty();
        }
        return Optional.of(new WeeklyDigestPayload(
                window,
                rows.stream().map(row -> row.feedback().getId()).toList(),
                changes.stream().map(WeeklyDigestChangeKey::of).distinct().toList()));
    }

    /** Several digests of one developer as they read now, from what each held, in order. */
    @Transactional(readOnly = true)
    public List<Rendered> render(long workspaceId, long accountId, List<WeeklyDigestPayload> payloads) {
        if (payloads.isEmpty()) {
            return List.of();
        }
        List<Instant> unread = practicePage.unreadCreatedAt(workspaceId, accountId);
        Set<UUID> held = payloads.stream()
                .flatMap(payload -> payload.feedbackIds().stream())
                .collect(Collectors.toSet());
        List<Row> rows =
                held.isEmpty() ? List.of() : rows(workspaceId, repository.findReached(workspaceId, accountId, held));
        List<Row> standing =
                rows.stream().filter(row -> row.withdrawnAt() == null).toList();
        Map<Long, ItemRefDTO> items = references.items(
                workspaceId,
                standing.stream()
                        .filter(row -> row.feedback().getDestination() == FeedbackDestination.ON_WORK)
                        .map(row -> row.feedback().getItemId())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
        Map<UUID, UUID> entries = conversationEntries(workspaceId, standing);
        Map<UUID, HephThreadLookup.Place> places =
                entries.isEmpty() ? Map.of() : hephThreads.places(workspaceId, Set.copyOf(entries.values()));
        Map<UUID, List<FeedbackBasis>> basisByFeedback = standing.isEmpty()
                ? Map.of()
                : basisRepository
                        .findByWorkspaceIdAndFeedbackIdInOrderByOrdinalAsc(
                                workspaceId,
                                standing.stream()
                                        .map(row -> row.feedback().getId())
                                        .toList())
                        .stream()
                        .collect(Collectors.groupingBy(FeedbackBasis::getFeedbackId));
        List<Rendered> rendered = new ArrayList<>(payloads.size());
        for (WeeklyDigestPayload payload : payloads) {
            Set<UUID> listed = Set.copyOf(payload.feedbackIds());
            rendered.add(rendered(
                    payload,
                    rows.stream()
                            .filter(row -> listed.contains(row.feedback().getId()))
                            .toList(),
                    changes(workspaceId, accountId, payload.window()),
                    // Waiting for you: Practice page feedback prepared in the week that is still unread now.
                    (int) unread.stream()
                            .filter(payload.window().overview()::contains)
                            .count(),
                    items,
                    entries,
                    places,
                    basisByFeedback));
        }
        return rendered;
    }

    private static Rendered rendered(
            WeeklyDigestPayload payload,
            List<Row> rows,
            List<ProfileChangeDTO> weekChanges,
            int waiting,
            Map<Long, ItemRefDTO> items,
            Map<UUID, UUID> entries,
            Map<UUID, HephThreadLookup.Place> places,
            Map<UUID, List<FeedbackBasis>> basisByFeedback) {
        WeeklyDigestWindow window = payload.window();
        Set<WeeklyDigestChangeKey> held = new HashSet<>(payload.changeKeys());
        List<ProfileChangeDTO> changes = weekChanges.stream()
                .filter(change -> held.contains(WeeklyDigestChangeKey.of(change)))
                .toList();
        Map<UUID, List<UUID>> resultIds = new LinkedHashMap<>();
        Map<UUID, List<UUID>> problemIds = new LinkedHashMap<>();
        for (Row row : rows) {
            UUID feedbackId = row.feedback().getId();
            List<FeedbackBasis> edges = basisByFeedback.get(feedbackId);
            if (row.withdrawnAt() == null && edges != null) {
                resultIds.put(
                        feedbackId,
                        edges.stream()
                                .map(FeedbackBasis::getResultId)
                                .distinct()
                                .toList());
                problemIds.put(
                        feedbackId,
                        edges.stream()
                                .map(FeedbackBasis::getProblemId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList());
            }
        }
        WeeklyDigestDTO digest = new WeeklyDigestDTO(
                window.weekStart(),
                window.start(),
                window.end(),
                window.zone().getId(),
                rows.stream()
                        .map(row -> {
                            UUID entryId = entries.get(row.feedback().getId());
                            HephThreadLookup.Place place = entryId == null ? null : places.get(entryId);
                            return place == null
                                    ? row.toDto(items, null, null)
                                    : row.toDto(items, entryId, place.threadParentId());
                        })
                        .toList(),
                changes,
                waiting);
        return new Rendered(digest, resultIds, problemIds);
    }

    /** The Practice profile's changes in the week, without the changes of Practice page feedback. */
    private List<ProfileChangeDTO> changes(long workspaceId, long accountId, WeeklyDigestWindow window) {
        return profileChanges.changes(workspaceId, accountId, window.overview()).stream()
                .filter(change -> !PRACTICE_PAGE_CHANGES.contains(change.type()))
                .toList();
    }

    /**
     * A listed piece of feedback that still shows.
     *
     * @param practice the practice of its newest evidence
     * @param withdrawnAt when a workspace admin withdrew it, or {@code null}
     */
    private record Row(
            Feedback feedback, Practice practice, @Nullable Instant withdrawnAt) {

        String practiceName() {
            return Objects.requireNonNull(practice.getCurrentVersion().getName());
        }

        Instant deliveredAt() {
            return Objects.requireNonNull(feedback.getDeliveredAt());
        }

        WeeklyDigestFeedbackDTO toDto(
                Map<Long, ItemRefDTO> items, @Nullable UUID mentorEntryId, @Nullable UUID mentorParentEntryId) {
            PracticeGroup group = practice.getGroup();
            boolean withdrawn = withdrawnAt != null;
            FeedbackDestination destination = feedback.getDestination();
            Long itemId = feedback.getItemId();
            return new WeeklyDigestFeedbackDTO(
                    feedback.getId(),
                    practice.getSlug(),
                    practiceName(),
                    group == null ? null : group.getSlug(),
                    group == null ? null : group.getName(),
                    destination,
                    destination != FeedbackDestination.ON_WORK || withdrawn || itemId == null
                            ? null
                            : items.get(itemId),
                    destination != FeedbackDestination.CONVERSATION || withdrawn ? null : mentorEntryId,
                    destination != FeedbackDestination.CONVERSATION || withdrawn ? null : mentorParentEntryId,
                    deliveredAt(),
                    withdrawn,
                    feedback.getState() == FeedbackState.REPLACED);
        }
    }

    /**
     * The pieces among {@code reached} that still show: evidence that may no longer be shown hides a piece whole. A
     * withdrawn piece shows by its practice only.
     */
    private List<Row> rows(long workspaceId, List<Feedback> reached) {
        if (reached.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = reached.stream().map(Feedback::getId).toList();
        Map<UUID, List<Result>> evidence = basis.visibleEvidence(workspaceId, ids);
        Map<Long, Practice> practices = basis.practices(
                workspaceId,
                evidence.values().stream().flatMap(Collection::stream).toList());
        Map<UUID, Instant> withdrawnAt = visibility.withdrawnAt(workspaceId, ids);
        List<Row> rows = new ArrayList<>();
        for (Feedback feedback : reached) {
            List<Result> support = evidence.get(feedback.getId());
            Practice practice =
                    support == null ? null : practices.get(support.getFirst().getPracticeId());
            if (practice != null) {
                rows.add(new Row(feedback, practice, withdrawnAt.get(feedback.getId())));
            }
        }
        rows.sort(CATALOG_ORDER);
        return rows;
    }

    /** The Heph message each conversation piece appeared in; {@link #render} drops the ones that were erased. */
    private Map<UUID, UUID> conversationEntries(long workspaceId, List<Row> rows) {
        List<UUID> conversational = rows.stream()
                .filter(row -> row.feedback().getDestination() == FeedbackDestination.CONVERSATION)
                .map(row -> row.feedback().getId())
                .toList();
        if (conversational.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        for (WeeklyDigestRepository.ConversationCopy copy :
                repository.conversationCopies(workspaceId, conversational)) {
            entries.putIfAbsent(copy.getFeedbackId(), copy.getMentorEntryId());
        }
        return entries;
    }
}
