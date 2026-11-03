package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.EvidenceCollection;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluates Applies when against captured evidence.
 *
 * <p>Only complete captures can prove absence. Missing, partial, malformed, or unsupported evidence is
 * {@link AppliesWhenCheck.Result#UNDECIDABLE} and keeps the practice eligible.
 */
@Component
@ConditionalOnCaptureRole
public class ScmCapturedApplicability implements CapturedApplicability {
    private static final AntPathMatcher PATHS = new AntPathMatcher("/");

    private final JsonMapper objectMapper;

    public ScmCapturedApplicability(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AppliesWhenCheck.Clause> evaluate(
            ItemKind kind, List<Condition> conditions, List<SourceCapture> sources, Map<String, byte[]> staged) {
        if (!kind.value().equals("scm.pull_request") && !kind.value().equals("scm.pull_request_review"))
            return List.of();
        Map<SourceKind, SourceCapture> captures = new HashMap<>();
        sources.forEach(capture -> captures.put(capture.kind(), capture));
        var change = ReviewChange.of(staged, objectMapper);
        List<AppliesWhenCheck.Clause> clauses = new ArrayList<>(conditions.size());
        for (Condition clause : conditions) {
            SourceKind readFrom = sourceOf(clause);
            AppliesWhenCheck.Result result = find(clause, captures.get(readFrom), change, staged);
            clauses.add(new AppliesWhenCheck.Clause(aspectOf(clause), readFrom, result));
        }
        return List.copyOf(clauses);
    }

    private AppliesWhenCheck.Result find(
            Condition clause,
            @Nullable SourceCapture capture,
            @Nullable ReviewChange change,
            Map<String, byte[]> staged) {
        if (capture == null || !(capture.state() instanceof SourceCaptureState.Available available)) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        if (available.completeness() != SourceCompleteness.COMPLETE) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        if (clause.merged()) return merged(capture, staged);
        if (available.content() == SourceContentState.EMPTY) {
            return AppliesWhenCheck.Result.NOT_FOUND;
        }
        return switch (aspectOf(clause)) {
            case "CHANGED_PATH" -> changedPath(clause.changedPath(), change);
            case "DIFF_CONTAINS" -> diffText(clause.diffContains(), change);
            case "HAS_ITEMS" -> evidenceItems(clause.hasItems(), capture, staged);
            default -> throw new IllegalArgumentException("Unknown applicability aspect");
        };
    }

    private AppliesWhenCheck.Result changedPath(@Nullable List<String> globs, @Nullable ReviewChange change) {
        if (globs == null || change == null) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        boolean matched = change.changedPaths().stream()
                .anyMatch(path -> globs.stream().anyMatch(glob -> PATHS.match(glob, path)));
        return matched ? AppliesWhenCheck.Result.FOUND : AppliesWhenCheck.Result.NOT_FOUND;
    }

    private AppliesWhenCheck.Result diffText(@Nullable List<String> literals, @Nullable ReviewChange change) {
        if (literals == null || change == null) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        String text = change.text();
        return literals.stream().anyMatch(text::contains)
                ? AppliesWhenCheck.Result.FOUND
                : AppliesWhenCheck.Result.NOT_FOUND;
    }

    private AppliesWhenCheck.Result evidenceItems(
            @Nullable String collection, SourceCapture capture, Map<String, byte[]> staged) {
        if (collection == null) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        String fileName = fileNameOf(collection);
        CapturedSource artifact = capture.files().stream()
                .filter(candidate -> candidate.path().endsWith(fileName))
                .findFirst()
                .orElse(null);
        byte @Nullable [] bytes = artifact == null ? null : staged.get(artifact.path());
        if (bytes == null || bytes.length == 0) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(bytes);
        } catch (RuntimeException unparseable) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        String field = fieldOf(collection);
        JsonNode items = field.isEmpty() ? root : root.path(field);
        if (!items.isArray()) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        return items.isEmpty() ? AppliesWhenCheck.Result.NOT_FOUND : AppliesWhenCheck.Result.FOUND;
    }

    /**
     * Only a record that says open proves no merge. A webhook can store a merged pull request as closed, or with a merge
     * time and {@code is_merged} false, so any other record that does not state the merge stays undecidable.
     */
    private AppliesWhenCheck.Result merged(SourceCapture capture, Map<String, byte[]> staged) {
        CapturedSource artifact = capture.files().stream()
                .filter(candidate -> candidate.path().endsWith("metadata.json"))
                .findFirst()
                .orElse(null);
        byte @Nullable [] bytes = artifact == null ? null : staged.get(artifact.path());
        if (bytes == null || bytes.length == 0) return AppliesWhenCheck.Result.UNDECIDABLE;
        JsonNode record;
        try {
            record = objectMapper.readTree(bytes);
        } catch (RuntimeException unparseable) {
            return AppliesWhenCheck.Result.UNDECIDABLE;
        }
        JsonNode merged = record.path("is_merged");
        if (merged.asBoolean(false) || record.path("merged_at").isString()) return AppliesWhenCheck.Result.FOUND;
        return merged.isBoolean() && "OPEN".equals(record.path("state").asString(""))
                ? AppliesWhenCheck.Result.NOT_FOUND
                : AppliesWhenCheck.Result.UNDECIDABLE;
    }

    private static SourceKind sourceOf(Condition clause) {
        if (clause.merged()) return PullRequestContentSource.CORE;
        return clause.hasItems() == null
                ? PullRequestContentSource.DIFF
                : EvidenceCollection.of(clause.hasItems()).sourceKind();
    }

    private static String aspectOf(Condition clause) {
        if (clause.merged()) return "MERGED";
        return clause.changedPath() != null
                ? "CHANGED_PATH"
                : clause.diffContains() != null ? "DIFF_CONTAINS" : "HAS_ITEMS";
    }

    private static String fileNameOf(String collection) {
        return switch (EvidenceCollection.of(collection)) {
            case SCM_INLINE_REVIEW_COMMENTS -> "comments.json";
            case SCM_REVIEW_THREADS -> ReviewThreadContentSource.FILE_NAME;
            case SCM_GENERAL_REVIEW_COMMENTS -> GeneralReviewCommentContentSource.FILE_NAME;
        };
    }

    private static String fieldOf(String collection) {
        return switch (EvidenceCollection.of(collection)) {
            case SCM_REVIEW_THREADS -> "threads";
            case SCM_GENERAL_REVIEW_COMMENTS -> "comments";
            case SCM_INLINE_REVIEW_COMMENTS -> "";
        };
    }
}
