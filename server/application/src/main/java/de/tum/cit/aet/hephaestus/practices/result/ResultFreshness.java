package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** The captured content dependencies of a result, compared with the work that can be read now. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultFreshness {
    private final ReviewPracticeRepository practices;
    private final ProductItemQuery items;

    public Map<UUID, Currentness> states(long workspaceId, Collection<Result> rows) {
        return currentness(freshnessFor(workspaceId, claims(rows)));
    }

    /**
     * The freshness of these results, with the reason that an unverifiable one cannot be checked and when each capture
     * read the work, for a caller that has already read their active items.
     */
    public Map<UUID, Freshness> freshness(long workspaceId, Collection<Result> rows, Map<Long, ItemRef> activeItems) {
        return freshnessFor(workspaceId, claims(rows), activeItems);
    }

    private static List<Claim> claims(Collection<Result> rows) {
        return rows.stream()
                .map(result -> new Claim(
                        result.getId(),
                        result.getWorkspaceId(),
                        result.getReviewId(),
                        result.getPracticeId(),
                        result.getItemId(),
                        result.getEndedAt()))
                .toList();
    }

    /** Only these identity and lifecycle fields are needed when a query filters freshness before pagination. */
    public record Claim(
            UUID id,
            long workspaceId,
            UUID reviewId,
            long practiceId,
            long itemId,
            @Nullable Instant endedAt) {}

    public Map<UUID, Currentness> statesFor(long workspaceId, Collection<Claim> rows) {
        return currentness(freshnessFor(workspaceId, rows));
    }

    private static Map<UUID, Currentness> currentness(Map<UUID, Freshness> freshness) {
        return freshness.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().currentness()));
    }

    private Map<UUID, Freshness> freshnessFor(long workspaceId, Collection<Claim> rows) {
        return freshnessFor(
                workspaceId,
                rows,
                rows.isEmpty()
                        ? Map.of()
                        : items.findAll(
                                workspaceId,
                                rows.stream().map(Claim::itemId).distinct().toList()));
    }

    private Map<UUID, Freshness> freshnessFor(long workspaceId, Collection<Claim> rows, Map<Long, ItemRef> refs) {
        if (rows.stream().anyMatch(result -> result.workspaceId() != workspaceId))
            throw new IllegalArgumentException("Results do not belong to the workspace");
        if (rows.isEmpty()) return Map.of();
        var captures = practices.sourceCaptures(
                workspaceId,
                rows.stream().map(Claim::reviewId).distinct().toArray(UUID[]::new),
                rows.stream().map(Claim::itemId).distinct().toArray(Long[]::new));
        var byCapture = captures.stream()
                .collect(Collectors.toMap(
                        capture -> new CaptureKey(capture.reviewId(), capture.practiceId(), capture.itemId()),
                        capture -> capture));
        var expected = new HashMap<UUID, Map<SourceKind, String>>();
        var capturedAt = new HashMap<UUID, Instant>();
        var occasions = new HashMap<UUID, UUID>();
        var frozen = new HashSet<UUID>();
        for (var result : rows) {
            var producingCapture = Optional.ofNullable(
                    byCapture.get(new CaptureKey(result.reviewId(), result.practiceId(), result.itemId())));
            // A scan reads its frozen scope, never the repository's current default branch.
            if (producingCapture
                    .filter(value -> value.reviewKind() == ReviewKind.SCAN)
                    .isPresent()) frozen.add(result.id());
            producingCapture
                    .map(ResultSourceCapture::occasion)
                    .ifPresent(occasion -> occasions.put(result.id(), occasion));
            var decision = producingCapture.stream()
                    .map(ResultSourceCapture::decision)
                    .filter(Objects::nonNull)
                    .findFirst();
            expected.put(
                    result.id(), decision.map(ResultFreshness::fingerprints).orElse(Map.of()));
            decision.map(ResultFreshness::decidedAt).ifPresent(at -> capturedAt.put(result.id(), at));
        }
        var states = new HashMap<UUID, Freshness>();
        for (var group : rows.stream()
                .collect(Collectors.groupingBy(result -> new ReadScope(
                        result.itemId(),
                        Objects.requireNonNull(expected.get(result.id())).keySet(),
                        occasions.get(result.id()))))
                .entrySet()) {
            var item = refs.get(group.getKey().itemId());
            var kinds = group.getKey().kinds();
            var current = item == null || kinds.isEmpty()
                    ? Map.<SourceKind, String>of()
                    : items.fingerprints(item, kinds, group.getKey().occasion());
            for (var result : group.getValue()) {
                var readAt = capturedAt.get(result.id());
                states.put(
                        result.id(),
                        result.endedAt() != null || item == null
                                ? Freshness.stale(readAt)
                                : frozen.contains(result.id())
                                        ? Freshness.current(readAt)
                                        : freshness(expected.getOrDefault(result.id(), Map.of()), current, readAt));
            }
        }
        return Map.copyOf(states);
    }

    /**
     * Brings this server's copy of what a freshness read of these results reads up to date with the provider, so that a
     * locked read after it does not answer from a mirror that another server filled or that this one evicted. It may
     * call the provider, so it runs with no transaction. A scan reads its frozen scope and needs nothing.
     */
    public void sync(long workspaceId, Collection<Result> rows) {
        var owned = rows.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .toList();
        if (owned.isEmpty()) return;
        var byCapture = practices
                .sourceCaptures(
                        workspaceId,
                        owned.stream().map(Result::getReviewId).distinct().toArray(UUID[]::new),
                        owned.stream().map(Result::getItemId).distinct().toArray(Long[]::new))
                .stream()
                .filter(capture -> capture.reviewKind() != ReviewKind.SCAN)
                .collect(Collectors.toMap(
                        capture -> new CaptureKey(capture.reviewId(), capture.practiceId(), capture.itemId()),
                        capture -> capture));
        var scopes = new HashSet<ReadScope>();
        for (var result : owned) {
            var capture =
                    byCapture.get(new CaptureKey(result.getReviewId(), result.getPracticeId(), result.getItemId()));
            if (capture == null) continue;
            var kinds = fingerprints(capture.decision()).keySet();
            if (!kinds.isEmpty()) scopes.add(new ReadScope(result.getItemId(), kinds, capture.occasion()));
        }
        if (scopes.isEmpty()) return;
        var refs = items.findAll(
                workspaceId, scopes.stream().map(ReadScope::itemId).distinct().toList());
        for (var scope : scopes) {
            var item = refs.get(scope.itemId());
            if (item != null) items.sync(item, scope.kinds(), scope.occasion());
        }
    }

    /** {@link #sync(long, Collection)} for the sources that one capture fingerprinted, before {@link #check}. */
    public void sync(long workspaceId, long itemId, @Nullable UUID occasion, @Nullable JsonNode facts) {
        var kinds = fingerprints(facts).keySet();
        if (kinds.isEmpty()) return;
        items.find(workspaceId, itemId).ifPresent(item -> items.sync(item, kinds, occasion));
    }

    private record CaptureKey(UUID reviewId, long practiceId, long itemId) {}

    private record ReadScope(
            long itemId, Set<SourceKind> kinds, @Nullable UUID occasion) {}

    /** A requested review can reuse only the same readable footprint that its own capture recorded. */
    public boolean sameCapture(long workspaceId, Result result, UUID requestedReviewId) {
        if (result.getWorkspaceId() != workspaceId)
            throw new IllegalArgumentException("The result does not belong to the workspace");
        var captured = practices.sourceCaptures(
                workspaceId, new UUID[] {result.getReviewId(), requestedReviewId}, new Long[] {result.getItemId()});
        var maps = new HashMap<UUID, Map<SourceKind, String>>();
        captured.stream()
                .filter(row -> row.practiceId() == result.getPracticeId())
                .forEach(row -> maps.put(row.reviewId(), fingerprints(row.decision())));
        var previous = maps.getOrDefault(result.getReviewId(), Map.of());
        return !previous.isEmpty() && previous.equals(maps.get(requestedReviewId));
    }

    public Currentness capture(long workspaceId, long itemId, @Nullable UUID occasion, @Nullable JsonNode facts) {
        return check(workspaceId, itemId, occasion, facts).currentness();
    }

    /**
     * Whether the sources that a capture fingerprinted still hold what it read, and which of them do not. The current
     * sources are read under {@code occasion}, the occasion that the capture read.
     *
     * @param changed the sources whose current fingerprint differs from the captured one
     * @param unread the captured sources that cannot be fingerprinted now
     */
    public record Check(Currentness currentness, Set<String> changed, Set<String> unread) {}

    public Check check(long workspaceId, long itemId, @Nullable UUID occasion, @Nullable JsonNode facts) {
        var captured = fingerprints(facts);
        var item = items.find(workspaceId, itemId);
        if (item.isEmpty()) return new Check(Currentness.STALE, Set.of(), Set.of());
        if (captured.isEmpty()) return new Check(Currentness.UNVERIFIABLE, Set.of(), Set.of());
        var current = items.fingerprints(item.orElseThrow(), captured.keySet(), occasion);
        var changed = new TreeSet<String>();
        var unread = new TreeSet<String>();
        captured.forEach((kind, fingerprint) -> {
            String now = current.get(kind);
            if (now == null) unread.add(kind.value());
            else if (!now.equals(fingerprint)) changed.add(kind.value());
        });
        return new Check(compare(captured, current), Set.copyOf(changed), Set.copyOf(unread));
    }

    private static Map<SourceKind, String> fingerprints(@Nullable JsonNode facts) {
        if (facts == null) return Map.of();
        var node = facts.path(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
        if (!node.isObject() || node.isEmpty()) return Map.of();
        var result = new HashMap<SourceKind, String>();
        for (var entry : node.properties()) {
            var value = entry.getValue();
            if (!value.isString() || !value.asString().matches("[0-9a-f]{64}")) return Map.of();
            try {
                result.put(new SourceKind(entry.getKey()), value.asString());
            } catch (IllegalArgumentException invalidKind) {
                return Map.of();
            }
        }
        return Map.copyOf(result);
    }

    /**
     * {@link #compare} with what is known about an unverifiable answer: a missing record can never be verified, while
     * a source that cannot be read now can be read later.
     */
    static Freshness freshness(
            Map<SourceKind, String> captured, Map<SourceKind, String> current, @Nullable Instant capturedAt) {
        if (captured.isEmpty()) return Freshness.recordMissing();
        var currentness = compare(captured, current);
        return new Freshness(
                currentness,
                currentness == Currentness.UNVERIFIABLE ? UnverifiableReason.WORK_UNREADABLE : null,
                capturedAt);
    }

    /** When the capture read its sources, as its readiness decision recorded it. */
    private static @Nullable Instant decidedAt(JsonNode decision) {
        var value = decision.path("decidedAt");
        if (!value.isString()) return null;
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    public static Currentness compare(Map<SourceKind, String> captured, Map<SourceKind, String> current) {
        if (captured.isEmpty()) return Currentness.UNVERIFIABLE;
        if (captured.entrySet().stream()
                .anyMatch(entry ->
                        current.containsKey(entry.getKey()) && !entry.getValue().equals(current.get(entry.getKey()))))
            return Currentness.STALE;
        return current.keySet().containsAll(captured.keySet()) ? Currentness.CURRENT : Currentness.UNVERIFIABLE;
    }
}
