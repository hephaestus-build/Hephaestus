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
import java.time.Instant;
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
        return statesFor(workspaceId, claims(rows));
    }

    /** {@link #states(long, Collection)} for a caller that has already read these results' active items. */
    public Map<UUID, Currentness> states(long workspaceId, Collection<Result> rows, Map<Long, ItemRef> activeItems) {
        return statesFor(workspaceId, claims(rows), activeItems);
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
        return statesFor(
                workspaceId,
                rows,
                rows.isEmpty()
                        ? Map.of()
                        : items.findAll(
                                workspaceId,
                                rows.stream().map(Claim::itemId).distinct().toList()));
    }

    private Map<UUID, Currentness> statesFor(long workspaceId, Collection<Claim> rows, Map<Long, ItemRef> refs) {
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
        }
        var states = new HashMap<UUID, Currentness>();
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
            for (var result : group.getValue())
                states.put(
                        result.id(),
                        result.endedAt() != null
                                ? Currentness.STALE
                                : item == null
                                        ? Currentness.STALE
                                        : frozen.contains(result.id())
                                                ? Currentness.CURRENT
                                                : compare(expected.getOrDefault(result.id(), Map.of()), current));
        }
        return Map.copyOf(states);
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

    public static Currentness compare(Map<SourceKind, String> captured, Map<SourceKind, String> current) {
        if (captured.isEmpty()) return Currentness.UNVERIFIABLE;
        if (captured.entrySet().stream()
                .anyMatch(entry ->
                        current.containsKey(entry.getKey()) && !entry.getValue().equals(current.get(entry.getKey()))))
            return Currentness.STALE;
        return current.keySet().containsAll(captured.keySet()) ? Currentness.CURRENT : Currentness.UNVERIFIABLE;
    }
}
