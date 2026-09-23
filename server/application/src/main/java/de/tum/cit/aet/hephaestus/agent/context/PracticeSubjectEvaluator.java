package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.PracticeSubjectCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SubjectClauseFinding;
import de.tum.cit.aet.hephaestus.evidence.SubjectEvidenceCollection;
import de.tum.cit.aet.hephaestus.evidence.SubjectFinding;
import de.tum.cit.aet.hephaestus.practices.PracticeSubject;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluates mechanical practice preconditions against staged evidence.
 *
 * <p>Only complete captures can prove absence. Missing, partial, malformed, or unsupported evidence is
 * {@link SubjectFinding#UNDECIDABLE} and keeps the practice eligible.
 */
@Component
public class PracticeSubjectEvaluator {

    private final JsonMapper objectMapper;

    public PracticeSubjectEvaluator(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param change the reviewed change, or null when none was prepared, which leaves every clause about
     *               the change undecided
     * @return {@code null} when the practice has no mechanical precondition
     */
    public @Nullable PracticeSubjectCheck evaluate(
            @Nullable PracticeSubject subject,
            ArtifactSourceManifest manifest,
            Map<String, byte[]> staged,
            @Nullable ReviewChange change) {
        if (subject == null) {
            return null;
        }
        Map<SourceKind, SourceCapture> captures = new HashMap<>();
        manifest.sources().forEach(capture -> captures.put(capture.kind(), capture));
        List<SubjectClauseFinding> clauses = new ArrayList<>(subject.anyOf().size());
        for (PracticeSubjectClause clause : subject.anyOf()) {
            SourceKind readFrom = clause.readsFrom();
            SubjectFinding finding = find(clause, captures.get(readFrom), change, staged);
            clauses.add(new SubjectClauseFinding(clause.aspect(), readFrom, finding));
        }
        boolean absent = clauses.stream().allMatch(clause -> clause.finding() == SubjectFinding.NOT_FOUND);
        return new PracticeSubjectCheck(absent, subject.absentSays(), clauses);
    }

    private SubjectFinding find(
            PracticeSubjectClause clause,
            @Nullable SourceCapture capture,
            @Nullable ReviewChange change,
            Map<String, byte[]> staged) {
        if (capture == null || !(capture.state() instanceof SourceCaptureState.Available available)) {
            return SubjectFinding.UNDECIDABLE;
        }
        if (available.completeness() != SourceCompleteness.COMPLETE) {
            return SubjectFinding.UNDECIDABLE;
        }
        if (available.content() == SourceContentState.EMPTY) {
            return SubjectFinding.NOT_FOUND;
        }
        return switch (clause.aspect()) {
            case CHANGED_PATH -> changedPath(clause.changedPathMatches(), change);
            case DIFF_TEXT -> diffText(clause.diffContains(), change);
            case EVIDENCE_ITEMS -> evidenceItems(clause.evidenceHasItems(), capture, staged);
        };
    }

    private SubjectFinding changedPath(@Nullable List<String> globs, @Nullable ReviewChange change) {
        if (globs == null || change == null) {
            return SubjectFinding.UNDECIDABLE;
        }
        List<Pattern> patterns =
                globs.stream().map(PracticeSubjectEvaluator::globToPattern).toList();
        boolean matched = change.changedPaths().stream()
                .anyMatch(
                        path -> patterns.stream().anyMatch(p -> p.matcher(path).matches()));
        return matched ? SubjectFinding.FOUND : SubjectFinding.NOT_FOUND;
    }

    private SubjectFinding diffText(@Nullable List<String> literals, @Nullable ReviewChange change) {
        if (literals == null || change == null) {
            return SubjectFinding.UNDECIDABLE;
        }
        String text = change.text();
        return literals.stream().anyMatch(text::contains) ? SubjectFinding.FOUND : SubjectFinding.NOT_FOUND;
    }

    private SubjectFinding evidenceItems(
            @Nullable SubjectEvidenceCollection collection, SourceCapture capture, Map<String, byte[]> staged) {
        if (collection == null) {
            return SubjectFinding.UNDECIDABLE;
        }
        String fileName = fileNameOf(collection);
        SourceArtifact artifact = capture.artifacts().stream()
                .filter(candidate -> candidate.path().endsWith(fileName))
                .findFirst()
                .orElse(null);
        byte @Nullable [] bytes = artifact == null ? null : staged.get(artifact.path());
        if (bytes == null || bytes.length == 0) {
            return SubjectFinding.UNDECIDABLE;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(bytes);
        } catch (RuntimeException unparseable) {
            return SubjectFinding.UNDECIDABLE;
        }
        String field = fieldOf(collection);
        JsonNode items = field.isEmpty() ? root : root.path(field);
        if (!items.isArray()) {
            return SubjectFinding.UNDECIDABLE;
        }
        return items.isEmpty() ? SubjectFinding.NOT_FOUND : SubjectFinding.FOUND;
    }

    private static String fileNameOf(SubjectEvidenceCollection collection) {
        return switch (collection) {
            case SCM_REVIEW_THREADS -> "review_threads.json";
            case SCM_INLINE_REVIEW_COMMENTS -> "comments.json";
            case SCM_GENERAL_REVIEW_COMMENTS -> "general_comments.json";
        };
    }

    /** The field holding the entries, or empty where the document is itself the array. */
    private static String fieldOf(SubjectEvidenceCollection collection) {
        return switch (collection) {
            case SCM_REVIEW_THREADS -> "threads";
            case SCM_INLINE_REVIEW_COMMENTS -> "";
            case SCM_GENERAL_REVIEW_COMMENTS -> "comments";
        };
    }

    /**
     * Translates the glob vocabulary — {@code **}, {@code *}, {@code ?}, and literals — into a regular
     * expression. Hand-rolled rather than {@code FileSystem.getPathMatcher}, which parses the subject as
     * a {@code Path}: a diff can name a path this platform's file system would reject, and the answer to
     * that must not depend on which platform the worker runs on.
     *
     * <p>{@code **}{@code /} matches zero or more leading segments, so {@code **}{@code /pom.xml} finds a
     * root {@code pom.xml} as well as a nested one. Requiring both forms is the mistake every author
     * would otherwise make once, in the direction that silently skips a practice.
     */
    static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2).append('^');
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '*' && index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                if (index + 2 < glob.length() && glob.charAt(index + 2) == '/') {
                    regex.append("(?:.*/)?");
                    index += 3;
                } else {
                    regex.append(".*");
                    index += 2;
                }
                continue;
            }
            switch (current) {
                case '*' -> regex.append("[^/]*");
                case '?' -> regex.append("[^/]");
                default -> {
                    if (current < 128 && Character.isLetterOrDigit(current)) {
                        regex.append(current);
                    } else {
                        regex.append(Pattern.quote(String.valueOf(current)));
                    }
                }
            }
            index++;
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
