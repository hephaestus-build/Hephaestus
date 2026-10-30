package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.BasisToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks SCM reference semantics against the pinned item and captured range, never live work. */
@Component
@ConditionalOnCaptureRole
public class ScmCitationVerifier implements CitationVerifier {
    /** A string inside one check run of a record: the record path, then the run's index. */
    private static final Pattern CHECK_RUN_POINTER = Pattern.compile("([^#]+)#/head_check_runs/runs/(\\d+)(?:/.*)?");

    private final ObjectMapper mapper;

    public ScmCitationVerifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(
                ScmItemEvents.ISSUE,
                ScmItemEvents.PULL_REQUEST,
                ScmItemEvents.PULL_REQUEST_REVIEW,
                ScmItemEvents.REPOSITORY,
                ScmItemEvents.FILE);
    }

    @Override
    public List<CitationRefusal> verify(CitationCheck check) {
        if (!kinds().contains(check.item().kind())) throw new IllegalArgumentException("Unsupported SCM citation kind");
        boolean change = check.item().kind().equals(ScmItemEvents.PULL_REQUEST)
                || check.item().kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW);
        String head = null;
        String base = null;
        try {
            if (change) {
                if (!check.version().value().startsWith(check.item().kind().value() + ":v1:"))
                    throw new IllegalArgumentException("The SCM version does not belong to this item kind");
                head = ScmVersion.reviewedHead(check.version());
                base = Objects.requireNonNull(check.version().baseVersion());
            } else if (check.item().kind().equals(ScmItemEvents.FILE)) {
                head = Objects.requireNonNull(check.version().baseVersion());
            } else if (check.item().kind().equals(ScmItemEvents.REPOSITORY))
                head = check.version().value();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return List.of(refusal(-1, "The pinned SCM version has no verifiable repository range"));
        }
        if (head != null && !head.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            return List.of(refusal(-1, "The pinned repository version is not a full Git identity"));
        if (check.baseVersion() != null
                && !check.baseVersion().value().equals(check.version().baseVersion()))
            return List.of(refusal(-1, "The supplied base version contradicts the pinned SCM item"));
        List<CitationRefusal> refused = new ArrayList<>();
        for (var citation : check.citations()) {
            if (!code(citation)) {
                if (citation.side() != CitationSide.NEW || citation.commit() != null)
                    refused.add(refusal(
                            citation.index(), "This SCM record does not support a repository commit or base side"));
                else if (unfinishedRun(check, citation))
                    refused.add(new CitationRefusal(
                            citation.index(),
                            CandidateRefusal.UNFINISHED_RUN_CITED,
                            "The citation names a check run that had not finished, which has no outcome"));
                continue;
            }
            if (head == null || !safePath(citation.path())) {
                refused.add(refusal(citation.index(), "The citation does not belong to this repository item"));
                continue;
            }
            boolean diff = citation.source().equals(PullRequestContentSource.DIFF.value());
            String expected = citation.side() == CitationSide.OLD ? base : head;
            boolean valid = diff
                    ? change
                            && expected != null
                            && (citation.commit() == null || expected.equals(citation.commit()))
                            && capturedRange(check, base, head)
                            && capturedPath(check, citation)
                    : citation.side() == CitationSide.NEW
                            && (citation.commit() == null || citation.commit().matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                            && (change || citation.commit() == null || head.equals(citation.commit()));
            if (!valid) refused.add(refusal(citation.index(), "The citation refers to another SCM commit or side"));
        }
        var changedFiles = capturedEdits(check, base, head);
        for (BasisToCheck basis : check.bases()) {
            CitationToCheck newest = find(check.citations(), basis.headCitation());
            CitationToCheck oldest =
                    basis.baseCitation() == null ? null : find(check.citations(), basis.baseCitation());
            if (newest == null || (basis.type().equals("worsened") && oldest == null)) {
                refused.add(new CitationRefusal(
                        basis.headCitation(),
                        CandidateRefusal.BASIS_CITATION_OUT_OF_RANGE,
                        "The basis does not identify its captured citations"));
            } else if (!change
                    || !Set.of("introduced", "worsened").contains(basis.type())
                    || (code(newest) && !basisCommit(newest, CitationSide.NEW, head))
                    || (oldest != null && code(oldest) && !basisCommit(oldest, CitationSide.OLD, base))) {
                refused.add(refusal(
                        basis.headCitation(),
                        "The problem basis cites another repository commit than the reviewed change"));
            } else if (code(newest) && !intersectsChange(changedFiles, newest, CitationSide.NEW)) {
                refused.add(new CitationRefusal(
                        basis.headCitation(),
                        CandidateRefusal.BASIS_WITNESS_MISSING,
                        "The code basis head witness must intersect added lines in the pinned change"));
            }
        }
        return List.copyOf(refused);
    }

    private List<? extends FileHeader> capturedEdits(
            CitationCheck check, @Nullable String base, @Nullable String head) {
        if (head == null || !capturedRange(check, base, head)) return List.of();
        try {
            var change = mapper.readTree(
                    Files.readAllBytes(check.evidence().source().resolve(PullRequestContentSource.CHANGE_FILE)));
            var patch = new Patch();
            byte[] bytes = change.path("diff").asString("").getBytes(StandardCharsets.UTF_8);
            patch.parse(bytes, 0, bytes.length);
            return patch.getErrors().isEmpty() ? patch.getFiles() : List.of();
        } catch (IOException | RuntimeException failed) {
            return List.of();
        }
    }

    private static boolean intersectsChange(
            List<? extends FileHeader> files, CitationToCheck citation, CitationSide side) {
        for (var file : files) {
            if (!citation.path().equals(side == CitationSide.NEW ? file.getNewPath() : file.getOldPath())) continue;
            for (var edit : file.toEditList()) {
                int first = side == CitationSide.NEW ? edit.getBeginB() : edit.getBeginA();
                int end = side == CitationSide.NEW ? edit.getEndB() : edit.getEndA();
                if (first < end && citation.startLine() <= end && citation.endLine() > first) return true;
            }
        }
        return false;
    }

    private boolean capturedRange(CitationCheck check, @Nullable String base, String head) {
        Path root = check.evidence().source();
        Path path = root.resolve(PullRequestContentSource.CHANGE_FILE).normalize();
        try {
            if (!path.startsWith(root)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 64L * 1024 * 1024
                    || !path.toRealPath().startsWith(root.toRealPath())) return false;
            var range = mapper.readTree(Files.readAllBytes(path));
            return Objects.equals(base, range.path("base_sha").asString())
                    && head.equals(range.path("head_sha").asString());
        } catch (IOException | RuntimeException failed) {
            return false;
        }
    }

    private static boolean basisCommit(CitationToCheck citation, CitationSide expectedSide, @Nullable String commit) {
        if (commit == null) return false;
        if (citation.source().equals(PullRequestContentSource.DIFF.value()))
            return citation.side() == expectedSide && (citation.commit() == null || commit.equals(citation.commit()));
        return commit.equals(citation.commit());
    }

    /**
     * Whether the citation shows a check run of the captured pull request record that had not finished when it was
     * read: a string inside that run, or lines of the whole record that cover it. Such a run has no result, so it
     * supports no claim about the build or the tests. A pointer elsewhere in the record names no run.
     */
    private boolean unfinishedRun(CitationCheck check, CitationToCheck citation) {
        if (!citation.source().equals(PullRequestContentSource.CHECKS.value())) return false;
        Matcher pointer = CHECK_RUN_POINTER.matcher(citation.path());
        boolean intoRun = pointer.matches();
        if (!intoRun && citation.path().contains("#")) return false;
        String recordPath = intoRun ? pointer.group(1) : citation.path();
        if (!safePath(recordPath)) return false;
        try {
            List<RunLines> runs =
                    runLines(Files.readAllBytes(check.evidence().source().resolve(recordPath)));
            if (intoRun) {
                int index = Integer.parseInt(pointer.group(2));
                return index >= runs.size() || !runs.get(index).finished();
            }
            return runs.stream()
                    .anyMatch(run ->
                            !run.finished() && run.first() <= citation.endLine() && run.last() >= citation.startLine());
        } catch (IOException | RuntimeException unreadable) {
            return intoRun;
        }
    }

    /** Where each check run of a record stands in its text, and whether it had finished. */
    private record RunLines(long first, long last, boolean finished) {}

    private List<RunLines> runLines(byte[] record) {
        JsonNode runs = mapper.readTree(record).path("head_check_runs").path("runs");
        if (!runs.isArray() || runs.isEmpty()) return List.of();
        List<RunLines> lines = new ArrayList<>();
        try (JsonParser parser = mapper.createParser(record)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return List.of();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if (!"head_check_runs".equals(field)) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String inner = parser.currentName();
                    parser.nextToken();
                    if (!"runs".equals(inner)) {
                        parser.skipChildren();
                        continue;
                    }
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        long first = parser.currentTokenLocation().getLineNr();
                        parser.skipChildren();
                        long last = parser.currentTokenLocation().getLineNr();
                        lines.add(new RunLines(
                                first,
                                last,
                                runs.path(lines.size()).path("finished").asBoolean(false)));
                    }
                    return lines;
                }
            }
        }
        return lines;
    }

    private static boolean code(CitationToCheck citation) {
        return citation.source().equals(PullRequestContentSource.DIFF.value())
                || citation.source().equals(RepositoryTreeContentSource.KIND.value());
    }

    private static boolean safePath(String path) {
        if (path.isBlank() || path.contains("\\") || path.indexOf('\0') >= 0) return false;
        try {
            Path value = Path.of(path);
            return !value.isAbsolute() && value.normalize().equals(value) && !value.startsWith("..");
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static boolean capturedPath(CitationCheck check, CitationToCheck citation) {
        Path root = check.evidence().source();
        Path file = root.resolve("context/citable/" + citation.side().name().toLowerCase(Locale.ROOT))
                .resolve(citation.path())
                .normalize();
        try {
            return file.startsWith(root)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException failed) {
            return false;
        }
    }

    private static @Nullable CitationToCheck find(List<CitationToCheck> citations, int index) {
        return citations.stream()
                .filter(citation -> citation.index() == index)
                .findFirst()
                .orElse(null);
    }

    private static CitationRefusal refusal(int index, String message) {
        return new CitationRefusal(index, CandidateRefusal.BASIS_REVISION_UNSUPPORTED, message);
    }
}
