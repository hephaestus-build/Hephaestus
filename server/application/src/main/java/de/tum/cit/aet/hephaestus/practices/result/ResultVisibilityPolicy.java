package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ResultVisibilityPolicy {

    private final EvidenceAuthorization evidenceAuthorization;
    private final ResultMarkRepository marks;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ResultSourceAuthorization sources;

    /**
     * The rows a standing may be read off at {@code at}, out of rows the standing reader already selected. Unlike
     * {@link #permitsAt}, a row need not hold its claim at {@code at}: the reader decides which ended live run still
     * stands. Every other fence holds at {@code at}: the workspace, recording before {@code at}, no Incorrect mark,
     * and {@link ResultSourceAuthorization#permitsHumanStanding}.
     *
     * <p>A row with current proof must be verifiable under the practice version current at {@code at}. A row
     * recorded before the current result model (evidence version 0) has no proof to verify: it counts only as an
     * earlier review, and the reader marks what it reads as such. Such a row may name no practice version, since the
     * earlier model did not always record one; the reader then says its criteria are unknown. A version it does name
     * must be one of this workspace's versions of the same practice. Nothing admitted here becomes quotable; quotes
     * keep {@link #permitsAt}.
     */
    public Set<UUID> permitsStandingAt(long workspaceId, Collection<Result> selected, Instant at) {
        var owned = selected.stream()
                .filter(r ->
                        r.getWorkspaceId() == workspaceId && !r.getRecordedAt().isAfter(at))
                .toList();
        if (owned.isEmpty()) return Set.of();
        var pinned = pinnedVersions(workspaceId, owned);
        var current =
                versions
                        .findAt(
                                workspaceId,
                                owned.stream()
                                        .map(Result::getPracticeId)
                                        .distinct()
                                        .toList(),
                                at)
                        .stream()
                        .collect(Collectors.toMap(
                                v -> Objects.requireNonNull(v.getPractice().getId()), Function.identity()));
        var incorrect = incorrectAt(workspaceId, owned, at);
        return sources.permitsHumanStanding(
                workspaceId,
                owned.stream()
                        .filter(r -> !incorrect.contains(r.getId()) && current.containsKey(r.getPracticeId()))
                        .filter(r -> {
                            var version =
                                    r.getPracticeVersionId() == null ? null : pinned.get(r.getPracticeVersionId());
                            if (r.getEvidenceVersion() == 0) {
                                return r.getPracticeVersionId() == null
                                        || (version != null
                                                && Objects.equals(
                                                        version.getPractice().getId(), r.getPracticeId()));
                            }
                            var currentVersion = current.get(r.getPracticeId());
                            return currentVersion != null
                                    && verifiable(r, version)
                                    && Objects.requireNonNull(version)
                                            .getReviewDigest()
                                            .equals(currentVersion.getReviewDigest());
                        })
                        .toList());
    }

    /** Runs {@code read} as one read of the workspace, as {@link ResultSourceAuthorization#asOneRead} describes. */
    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        return sources.asOneRead(workspaceId, read);
    }

    private Set<UUID> incorrectAt(long workspaceId, List<Result> owned, Instant at) {
        return marks
                .findByWorkspaceIdAndResultIdIn(
                        workspaceId, owned.stream().map(Result::getId).toList())
                .stream()
                .filter(m -> m.getKind() == ResultMarkKind.INCORRECT
                        && !m.getCreatedAt().isAfter(at)
                        && (m.getRemovedAt() == null || m.getRemovedAt().isAfter(at)))
                .map(m -> m.getResultId())
                .collect(Collectors.toSet());
    }

    public Set<UUID> permitsAll(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        return permitted(workspaceId, results, purpose, false);
    }

    public Set<UUID> permitsForNewDelivery(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        return permitted(workspaceId, results, purpose, true);
    }

    public Set<UUID> permitsShown(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        List<Result> owned = results.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .toList();
        Map<Long, PracticeVersion> pinned = pinnedVersions(workspaceId, owned);
        List<Result> verifiable = owned.stream()
                .filter(result -> result.getEvidenceVersion() == 0
                        || (result.getPracticeVersionId() != null && pinned.containsKey(result.getPracticeVersionId())))
                .toList();
        return evidenceAuthorization.permitsAll(workspaceId, withoutIncorrect(workspaceId, verifiable), purpose);
    }

    public Set<UUID> permitsHistory(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        return evidenceAuthorization.permitsAll(
                workspaceId,
                results.stream()
                        .filter(result -> result.getWorkspaceId() == workspaceId)
                        .toList(),
                purpose);
    }

    public static Currentness currentness(Result result, @Nullable PracticeVersion pinned, PracticeVersion current) {
        if (result.getEvidenceVersion() == 0) return Currentness.UNVERIFIABLE;
        if (result.getEndedAt() != null) {
            return Currentness.STALE;
        }
        if (!verifiable(result, pinned)) return Currentness.UNVERIFIABLE;
        Objects.requireNonNull(pinned);
        return pinned.getReviewDigest().equals(current.getReviewDigest()) ? Currentness.CURRENT : Currentness.STALE;
    }

    private static boolean verifiable(Result result, @Nullable PracticeVersion pinned) {
        return pinned != null
                && result.getEvidenceVersion() != 0
                && result.getSummary() != null
                && result.getExplanation() != null
                && result.getEvidence() != null
                && result.getCoverage() != null;
    }

    public Set<UUID> permitsAt(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose, Instant at) {
        var owned = rows.stream()
                .filter(r -> r.getWorkspaceId() == workspaceId
                        && !r.getRecordedAt().isAfter(at)
                        && (r.getEndedAt() == null || r.getEndedAt().isAfter(at)))
                .toList();
        var pinned = pinnedVersions(workspaceId, owned);
        var current =
                versions
                        .findAt(
                                workspaceId,
                                owned.stream()
                                        .map(Result::getPracticeId)
                                        .distinct()
                                        .toList(),
                                at)
                        .stream()
                        .collect(Collectors.toMap(
                                v -> Objects.requireNonNull(v.getPractice().getId()), Function.identity()));
        var incorrect =
                marks
                        .findByWorkspaceIdAndResultIdIn(
                                workspaceId, owned.stream().map(Result::getId).toList())
                        .stream()
                        .filter(m -> m.getKind() == ResultMarkKind.INCORRECT
                                && !m.getCreatedAt().isAfter(at)
                                && (m.getRemovedAt() == null || m.getRemovedAt().isAfter(at)))
                        .map(m -> m.getResultId())
                        .collect(Collectors.toSet());
        return evidenceAuthorization.permitsAll(
                workspaceId,
                owned.stream()
                        .filter(r -> !incorrect.contains(r.getId())
                                && current.containsKey(r.getPracticeId())
                                && verifiable(r, pinned.get(r.getPracticeVersionId()))
                                && Objects.requireNonNull(pinned.get(r.getPracticeVersionId()))
                                        .getReviewDigest()
                                        .equals(current.get(r.getPracticeId()).getReviewDigest()))
                        .toList(),
                purpose);
    }

    private Set<UUID> permitted(
            long workspaceId, Collection<Result> results, SourceUsePurpose purpose, boolean newDelivery) {
        List<Result> owned = results.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .toList();
        if (owned.isEmpty()) {
            return Set.of();
        }
        Map<Long, PracticeVersion> pinned = pinnedVersions(workspaceId, owned);
        Map<Long, Practice> current = practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        owned.stream().map(Result::getPracticeId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(practice -> Objects.requireNonNull(practice.getId()), Function.identity()));
        List<Result> eligible = owned.stream()
                .filter(result -> {
                    Practice practice = current.get(result.getPracticeId());
                    return practice != null
                            && currentness(
                                            result,
                                            pinned.get(result.getPracticeVersionId()),
                                            practice.getCurrentVersion())
                                    == Currentness.CURRENT;
                })
                .toList();
        List<Result> unmarked = withoutIncorrect(workspaceId, eligible);
        return newDelivery
                ? evidenceAuthorization.permitsForNewDelivery(workspaceId, unmarked, purpose)
                : evidenceAuthorization.permitsAll(workspaceId, unmarked, purpose);
    }

    private Map<Long, PracticeVersion> pinnedVersions(long workspaceId, Collection<Result> results) {
        Set<Long> ids = results.stream()
                .map(Result::getPracticeVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return versions.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
                .collect(Collectors.toMap(version -> Objects.requireNonNull(version.getId()), Function.identity()));
    }

    private List<Result> withoutIncorrect(long workspaceId, List<Result> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        Set<UUID> incorrect = marks
                .findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(
                        workspaceId, results.stream().map(Result::getId).toList())
                .stream()
                .filter(mark -> mark.getKind() == ResultMarkKind.INCORRECT)
                .map(mark -> mark.getResultId())
                .collect(Collectors.toSet());
        return results.stream()
                .filter(result -> !incorrect.contains(result.getId()))
                .toList();
    }
}
