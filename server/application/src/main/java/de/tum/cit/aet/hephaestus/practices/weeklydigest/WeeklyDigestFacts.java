package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.profile.OverviewWindow;
import de.tum.cit.aet.hephaestus.practices.profile.ProfileChangeDetector;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.HephThreadLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * ({@link #render}), each through the rules of the surface that owns it, so a digest never shows what that surface
 * would hide. Deterministic, with no model, and it changes no delivery state. Feedback is what reached the developer
 * in the week ({@link FeedbackRepository#DELIVERED_BETWEEN}). Changes are the Practice profile's own
 * ({@link ProfileChangeDetector}): a later read can lose a change, never gain one.
 */
@Service
@RequiredArgsConstructor
public class WeeklyDigestFacts {

    /** By practice group, then by the practice's place in the catalog. */
    private static final Comparator<Row> CATALOG_ORDER = Comparator.<Row, Boolean>comparing(
                    row -> row.practice().getGroup() == null)
            .thenComparingInt(row -> {
                PracticeGroup group = row.practice().getGroup();
                return group == null ? 0 : group.getDisplayOrder();
            })
            .thenComparing(row -> {
                PracticeGroup group = row.practice().getGroup();
                return group == null ? "" : group.getName();
            })
            .thenComparingInt(row -> row.practice().getDisplayOrder())
            .thenComparing(row -> row.practice().getName())
            .thenComparing(row -> Objects.requireNonNullElse(row.feedback().getDeliveredAt(), Instant.EPOCH));

    private final FeedbackRepository feedbackRepository;
    private final FeedbackPlacementRepository placementRepository;
    private final InAppFeedbackEvidence feedbackEvidence;
    private final InAppFeedbackService inAppFeedbackService;
    private final ProfileChangeDetector changeDetector;
    private final ObservationRepository observationRepository;
    private final ReactionRepository reactionRepository;
    private final ReviewRunLookup reviewRunLookup;
    private final HephThreadLookup hephThreads;
    private final Clock clock;

    /**
     * A digest as it reads now. Heph reads it as one JSON document: the digest's fields, and {@code observationIds}.
     *
     * @param observationIds by feedback id, the evidence behind each listed piece that still shows and is not
     *     withdrawn, for {@code link_observation}
     */
    public record Rendered(@JsonUnwrapped WeeklyDigestDTO digest, Map<UUID, List<UUID>> observationIds) {}

    /**
     * Whether anything happened for the developer in the week: feedback reached them or was prepared for their
     * Practice profile, a review recorded something about them, or they answered a piece of feedback. A cheap check
     * before {@link #compose}; only {@link #compose} decides.
     */
    @Transactional(readOnly = true)
    public boolean anythingIn(long workspaceId, long developerId, WeeklyDigestWindow window) {
        Instant since = window.start();
        Instant until = window.end();
        return feedbackRepository.existsReceivedBetween(
                        workspaceId, developerId, since, until, FeedbackDeliveryState.REACHED)
                || observationRepository
                        .existsByWorkspaceIdAndAboutUserIdAndObservedAtGreaterThanAndObservedAtLessThanEqual(
                                workspaceId, developerId, since, until)
                || !reactionRepository
                        .findInAppResolvedByDeveloperBetween(
                                developerId, workspaceId, since, until, FeedbackResolution.resolvingNames())
                        .isEmpty();
    }

    /**
     * What a digest for the week holds, or nothing when the week was quiet. A week is quiet unless feedback that
     * still stands reached the developer in it, something changed on their Practice profile in it, or a card
     * prepared in it waits unread: an old unread card alone does not send a digest every week.
     */
    @Transactional(readOnly = true)
    public Optional<WeeklyDigestPayload> compose(long workspaceId, long developerId, WeeklyDigestWindow window) {
        List<UUID> delivered = feedbackRepository
                .findDeliveredForRecipientBetween(
                        workspaceId, developerId, window.start(), window.end(), FeedbackDeliveryState.REACHED)
                .stream()
                .map(Feedback::getId)
                .toList();
        List<Row> rows = rows(workspaceId, developerId, delivered);
        List<ProfileChangeDTO> changes = changeDetector
                .detectWithoutNewFeedback(workspaceId, developerId, List.of(window.overview()))
                .getFirst();
        OverviewWindow week = window.overview();
        boolean unreadFromTheWeek = inAppFeedbackService.unreadPreparedAt(workspaceId, developerId).stream()
                .anyMatch(week::contains);
        boolean feedbackStands = rows.stream().anyMatch(row -> row.withdrawnAt() == null);
        if (!feedbackStands && changes.isEmpty() && !unreadFromTheWeek) {
            return Optional.empty();
        }
        return Optional.of(new WeeklyDigestPayload(
                window,
                rows.stream().map(row -> row.feedback().getId()).toList(),
                changes.stream().map(WeeklyDigestChangeKey::of).distinct().toList()));
    }

    /**
     * Several digests of one developer as they read now, from what each held, in order. Every read serves all of
     * them at once.
     */
    @Transactional(readOnly = true)
    public List<Rendered> render(long workspaceId, long developerId, List<WeeklyDigestPayload> payloads) {
        if (payloads.isEmpty()) {
            return List.of();
        }
        int waiting =
                inAppFeedbackService.unreadPreparedAt(workspaceId, developerId).size();
        List<List<ProfileChangeDTO>> changes = changeDetector.detectWithoutNewFeedback(
                workspaceId,
                developerId,
                payloads.stream().map(payload -> payload.window().overview()).toList());
        List<Row> rows = rows(
                workspaceId,
                developerId,
                payloads.stream()
                        .flatMap(payload -> payload.feedbackIds().stream())
                        .collect(Collectors.toSet()));
        Map<UUID, Target> targets = reviewRunLookup.findTargets(
                workspaceId,
                rows.stream()
                        .filter(row -> row.feedback().getChannel() == FeedbackChannel.IN_CONTEXT)
                        .map(row -> row.feedback().getAgentJobId())
                        .collect(Collectors.toSet()));
        Map<UUID, UUID> entries = conversationEntries(rows);
        Map<UUID, HephThreadLookup.Place> places =
                entries.isEmpty() ? Map.of() : hephThreads.places(workspaceId, Set.copyOf(entries.values()));
        List<Rendered> rendered = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            Set<UUID> held = Set.copyOf(payloads.get(i).feedbackIds());
            rendered.add(rendered(
                    payloads.get(i),
                    rows.stream()
                            .filter(row -> held.contains(row.feedback().getId()))
                            .toList(),
                    changes.get(i),
                    waiting,
                    targets,
                    entries,
                    places));
        }
        return rendered;
    }

    private Rendered rendered(
            WeeklyDigestPayload payload,
            List<Row> rows,
            List<ProfileChangeDTO> weekChanges,
            int waiting,
            Map<UUID, Target> targets,
            Map<UUID, UUID> entries,
            Map<UUID, HephThreadLookup.Place> places) {
        WeeklyDigestWindow window = payload.window();
        Set<WeeklyDigestChangeKey> held = new HashSet<>(payload.changeKeys());
        List<ProfileChangeDTO> changes = weekChanges.stream()
                .filter(change -> held.contains(WeeklyDigestChangeKey.of(change)))
                .toList();
        Map<UUID, List<UUID>> observationIds = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.withdrawnAt() == null) {
                observationIds.put(
                        row.feedback().getId(),
                        row.evidence().stream().map(Observation::getId).toList());
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
                                    ? row.toDto(targets, null, null)
                                    : row.toDto(targets, entryId, place.threadParentId());
                        })
                        .toList(),
                changes,
                waiting);
        return new Rendered(digest, observationIds);
    }

    /**
     * A listed piece of feedback that still shows.
     *
     * @param evidence what it rests on that may still be shown, newest first
     * @param withdrawnAt when a workspace admin withdrew it, or {@code null}
     */
    private record Row(
            Feedback feedback,
            List<Observation> evidence,
            @Nullable Instant withdrawnAt) {

        Practice practice() {
            return evidence.getFirst().getPractice();
        }

        WeeklyDigestFeedbackDTO toDto(
                Map<UUID, Target> targets, @Nullable UUID mentorEntryId, @Nullable UUID mentorParentEntryId) {
            Practice practice = practice();
            PracticeGroup group = practice.getGroup();
            boolean withdrawn = withdrawnAt != null;
            FeedbackChannel channel = feedback.getChannel();
            String headline = InAppFeedbackBody.headlineOf(feedback.getBody());
            return new WeeklyDigestFeedbackDTO(
                    feedback.getId(),
                    practice.getSlug(),
                    practice.getName(),
                    group == null ? null : group.getSlug(),
                    group == null ? null : group.getName(),
                    channel,
                    channel != FeedbackChannel.IN_CONTEXT || withdrawn
                            ? null
                            : ReviewedWorkLabels.refOrNull(
                                    feedback.getArtifactKind(),
                                    feedback.getArtifactId(),
                                    targets.get(feedback.getAgentJobId())),
                    channel != FeedbackChannel.IN_APP || withdrawn
                            ? null
                            : Objects.requireNonNullElse(headline, practice.getName()),
                    channel != FeedbackChannel.IN_CHAT || withdrawn ? null : mentorEntryId,
                    channel != FeedbackChannel.IN_CHAT || withdrawn ? null : mentorParentEntryId,
                    Objects.requireNonNullElse(feedback.getDeliveredAt(), feedback.getCreatedAt()),
                    withdrawn,
                    feedback.getDeliveryState() == FeedbackDeliveryState.SUPERSEDED);
        }
    }

    /**
     * The pieces among {@code ids} that still show: evidence that may no longer be cited hides a piece whole, and a
     * withdrawn piece shows while the Practice profile keeps its withdrawn card.
     */
    private List<Row> rows(long workspaceId, long developerId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<Feedback> reached =
                feedbackRepository.findByWorkspaceIdAndRecipientUserIdAndIdIn(workspaceId, developerId, ids).stream()
                        .filter(feedback -> FeedbackDeliveryState.REACHED.contains(feedback.getDeliveryState()))
                        .toList();
        List<UUID> reachedIds = reached.stream().map(Feedback::getId).toList();
        Map<UUID, List<Observation>> evidence = feedbackEvidence.visibleEvidence(workspaceId, reachedIds);
        Map<UUID, Instant> withdrawnAt = inAppFeedbackService.withdrawnAt(workspaceId, reachedIds);
        return reached.stream()
                .filter(feedback -> evidence.containsKey(feedback.getId()))
                .map(feedback -> new Row(
                        feedback,
                        Objects.requireNonNull(evidence.get(feedback.getId())),
                        withdrawnAt.get(feedback.getId())))
                .filter(row -> InAppFeedbackService.stillOnThePage(row.withdrawnAt(), now))
                .sorted(CATALOG_ORDER)
                .toList();
    }

    /** The Heph message each conversational piece appeared in; {@link #render} drops the ones that were erased. */
    private Map<UUID, UUID> conversationEntries(List<Row> rows) {
        List<UUID> conversational = rows.stream()
                .filter(row -> row.feedback().getChannel() == FeedbackChannel.IN_CHAT)
                .map(row -> row.feedback().getId())
                .toList();
        if (conversational.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        for (FeedbackPlacement placement : placementRepository.findByFeedbackIdInAndPlacementType(
                conversational, PlacementType.CONVERSATION_TURN)) {
            UUID entryId = placement.getMentorEntryId();
            if (entryId != null) {
                entries.putIfAbsent(placement.getFeedbackId(), entryId);
            }
        }
        return entries;
    }
}
