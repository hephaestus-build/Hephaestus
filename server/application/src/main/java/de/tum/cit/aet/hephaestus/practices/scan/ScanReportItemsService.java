package de.tum.cit.aet.hephaestus.practices.scan;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanEvidencePolicy;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries.ItemFilter;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries.ProblemFilter;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportCursor;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.JudgedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ListedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.NotReviewedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ReusedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanPracticeReportDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportItemsPageDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportProblemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportProblemsPageDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.WithheldScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanReportItemsService {
    private final ScanReportService reports;
    private final ScanEvidencePolicy eligibility;
    private final ProductReferenceQuery references;
    private final ResultPresentation presentation;
    private final ResultRepository results;
    private final ReviewRepository membershipQuery;
    private final ReviewItemRepository members;
    private final ResultViewQuery views;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ProblemRepository problems;
    private final JsonMapper json;

    public ScanReportItemsPageDTO items(
            long workspaceId, UUID id, @Nullable String cursor, int size, ItemFilter filter) {
        var review = reports.require(workspaceId, id);
        reports.requireRaw(review);
        var after = cursor(cursor, id, false);
        var pinned = reports.recorded(review).practices().stream()
                .collect(Collectors.toMap(p -> Long.parseLong(p.practiceId()), p -> p));
        var keys = membershipQuery.items(review, after, size + 1, filter);
        boolean more = keys.size() > size;
        var page = keys.subList(0, Math.min(size, keys.size()));
        var ids = page.stream().map(k -> k.cursor().itemId()).distinct().toList();
        var refs = references.items(workspaceId, ids);
        var membersByKey = members.findByWorkspaceIdAndReviewIdAndItemIdIn(workspaceId, id, ids).stream()
                .collect(Collectors.toMap(m -> new Key(m.getPracticeId(), m.getItemId()), Function.identity()));
        var resultsById = results
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        page.stream()
                                .map(ScanMembershipQueries.ItemKey::resultId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Result::getId, Function.identity()));
        var claims = results.findByWorkspaceIdAndItemIdInAndEndedAtIsNullAndDeveloperIdIsNull(workspaceId, ids);
        var proofRows = new ArrayList<>(resultsById.values());
        proofRows.addAll(claims);
        var permitted = eligibility.permitted(review, proofRows);
        var unmarked = eligibility.unmarked(review, claims);
        var previous = presentation
                .lists(
                        workspaceId,
                        views.views(
                                workspaceId,
                                claims.stream()
                                        .filter(c -> permitted.contains(c.getId()) && unmarked.contains(c.getId()))
                                        .toList()),
                        permitted)
                .stream()
                .collect(Collectors.toMap(v -> v.id(), Function.identity()));
        var claimsByKey = claims.stream()
                .collect(Collectors.toMap(c -> new Key(c.getPracticeId(), c.getItemId()), c -> c.getId()));
        var current = practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        page.stream()
                                .map(k -> k.cursor().practiceId())
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Practice::getId, Function.identity()));
        var pinnedVersions = versions
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        resultsById.values().stream()
                                .map(Result::getPracticeVersionId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(PracticeVersion::getId, Function.identity()));
        var content = page.stream()
                .map(key -> {
                    var member = Objects.requireNonNull(membersByKey.get(
                            new Key(key.cursor().practiceId(), key.cursor().itemId())));
                    var row = new MemberRow(
                            member,
                            Objects.requireNonNull(refs.get(key.cursor().itemId())),
                            Objects.requireNonNull(pinned.get(key.cursor().practiceId())),
                            key.problemCount());
                    var result = resultsById.get(member.getResultId());
                    var practice = current.get(member.getPracticeId());
                    var currentness = result == null || practice == null
                            ? null
                            : ResultVisibilityPolicy.currentness(
                                    result,
                                    pinnedVersions.get(result.getPracticeVersionId()),
                                    practice.getCurrentVersion());
                    return item(
                            review,
                            row,
                            result,
                            permitted,
                            currentness,
                            previous.get(claimsByKey.get(new Key(member.getPracticeId(), member.getItemId()))));
                })
                .toList();
        return new ScanReportItemsPageDTO(content, more ? encode(page.getLast().cursor()) : null, more);
    }

    private ScanReportItemDTO item(
            Review review,
            MemberRow row,
            @Nullable Result result,
            Set<UUID> permitted,
            @Nullable Currentness current,
            @Nullable ResultListDTO previous) {
        var member = row.member();
        var p = row.practice();
        var version = Objects.requireNonNull(member.getItemVersion());
        var state = itemStatus(review, member);
        var reason = member.getReason() != null
                ? member.getReason()
                : review.getStopReason() == null ? null : review.getStopReason().name();
        if (result != null) {
            var permittedProof = permitted.contains(result.getId());
            return member.getState() == ReviewItemState.REUSED
                    ? new ReusedScanReportItemDTO(
                            row.item(),
                            p.practiceSlug(),
                            p.practiceName(),
                            version,
                            result.getId(),
                            result.getReviewId(),
                            result.getOutcome(),
                            result.getSeverity(),
                            permittedProof ? result.getSummary() : null,
                            row.problemCount(),
                            Objects.requireNonNull(current),
                            result.getRecordedAt(),
                            "REUSED")
                    : new JudgedScanReportItemDTO(
                            row.item(),
                            p.practiceSlug(),
                            p.practiceName(),
                            version,
                            "JUDGED",
                            result.getId(),
                            result.getReviewId(),
                            result.getOutcome(),
                            result.getSeverity(),
                            permittedProof ? result.getSummary() : null,
                            row.problemCount(),
                            Objects.requireNonNull(current),
                            result.getRecordedAt());
        }
        return switch (state) {
            case "WITHHELD" ->
                new WithheldScanReportItemDTO(
                        row.item(),
                        p.practiceSlug(),
                        p.practiceName(),
                        version,
                        "WITHHELD",
                        Objects.requireNonNull(member.getReason()));
            case "NOT_REVIEWED" ->
                new NotReviewedScanReportItemDTO(
                        row.item(), p.practiceSlug(), p.practiceName(), version, reason, previous, "NOT_REVIEWED");
            default ->
                new ListedScanReportItemDTO(
                        row.item(), p.practiceSlug(), p.practiceName(), version, "LISTED", reason, previous);
        };
    }

    private static String itemStatus(Review review, ReviewItem item) {
        boolean pending = item.getState() == ReviewItemState.LISTED || item.getState() == ReviewItemState.STAGED;
        return pending
                ? review.getStatus().isTerminal() ? "NOT_REVIEWED" : "LISTED"
                : item.getState().name();
    }

    public ScanReportProblemsPageDTO problems(
            long workspaceId, UUID id, @Nullable String cursor, int size, ProblemFilter filter) {
        var review = reports.require(workspaceId, id);
        reports.requireRaw(review);
        var after = cursor(cursor, id, true);
        var rows = problemRows(review, filter, after, size + 1);
        boolean more = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        return new ScanReportProblemsPageDTO(
                page.stream().map(ProblemRow::dto).toList(),
                more ? encode(page.getLast().key()) : null,
                more);
    }

    public List<ProblemRow> problemRows(
            Review review, ProblemFilter filter, @Nullable ScanReportCursor after, int limit) {
        var next = after;
        var frozen = reports.recorded(review);
        var pinned = frozen.practices().stream().collect(Collectors.toMap(p -> Long.parseLong(p.practiceId()), p -> p));
        var output = new ArrayList<ProblemRow>();
        while (output.size() < limit) {
            var keys = membershipQuery.problems(review, next, 256, filter);
            if (keys.isEmpty()) break;
            var rows = results
                    .findByWorkspaceIdAndIdIn(
                            review.getWorkspaceId(),
                            keys.stream()
                                    .map(ScanMembershipQueries.ProblemKey::resultId)
                                    .distinct()
                                    .toList())
                    .stream()
                    .collect(Collectors.toMap(Result::getId, r -> r));
            var allowed = eligibility.permitted(review, rows.values());
            var unmarked = eligibility.unmarked(review, rows.values());
            var refs = references.items(
                    review.getWorkspaceId(),
                    keys.stream().map(k -> k.cursor().itemId()).distinct().toList());
            var children = problems
                    .findByWorkspaceIdAndIdIn(
                            review.getWorkspaceId(),
                            keys.stream()
                                    .map(k -> Objects.requireNonNull(k.cursor().problemId()))
                                    .toList())
                    .stream()
                    .collect(Collectors.toMap(p -> p.getId(), Function.identity()));
            for (var key : keys) {
                var result = rows.get(key.resultId());
                if (result == null
                        || !allowed.contains(result.getId())
                        || (filter.openOnly() && !unmarked.contains(result.getId()))) continue;
                var child = Objects.requireNonNull(children.get(key.cursor().problemId()));
                var practice = Objects.requireNonNull(pinned.get(key.cursor().practiceId()));
                var item = Objects.requireNonNull(refs.get(key.cursor().itemId()));
                var dto = new ScanReportProblemDTO(
                        presentation.problem(child, true),
                        result.getId(),
                        item,
                        practice.practiceSlug(),
                        practice.practiceName(),
                        key.state(),
                        key.previousProblemId(),
                        key.state() == ProblemBaselineState.ABSENT ? "REPAIRED" : null);
                output.add(new ProblemRow(key.cursor(), dto, result));
                if (output.size() == limit) break;
            }
            if (keys.size() < 256) break;
            next = keys.getLast().cursor();
        }
        return List.copyOf(output);
    }

    private @Nullable ScanReportCursor cursor(@Nullable String value, UUID scanId, boolean problem) {
        if (value == null) return null;
        if (value.length() > 2048) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cursor is too long.");
        try {
            var decoded = Base64.getUrlDecoder().decode(value);
            var cursor = json.readerFor(ScanReportCursor.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(decoded);
            var typed = (ScanReportCursor) cursor;
            if (!typed.scanId().equals(scanId)
                    || typed.path() == null
                    || typed.path().length() > 1024
                    || typed.practiceId() < 1
                    || typed.itemId() < 1
                    || (typed.problemId() != null) != problem
                    || typed.position() < 0) throw new IllegalArgumentException("Invalid cursor identity");
            return typed;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The scan cursor is invalid.", failure);
        }
    }

    private String encode(ScanReportCursor cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.writeValueAsString(cursor).getBytes(StandardCharsets.UTF_8));
    }

    private record MemberRow(ReviewItem member, ItemRefDTO item, ScanPracticeReportDTO practice, int problemCount) {}

    record ProblemRow(ScanReportCursor key, ScanReportProblemDTO dto, Result result) {}

    private record Key(long practiceId, long itemId) {}
}
