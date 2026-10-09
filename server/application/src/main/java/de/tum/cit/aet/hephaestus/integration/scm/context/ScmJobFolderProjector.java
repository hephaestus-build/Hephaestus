package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentProvenance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true)
public class ScmJobFolderProjector implements WorkspaceScmProjection {
    /** The user ids a projected record may carry, each recorded as a copy of that person's data. */
    private static final List<String> PERSON_FIELDS =
            List.of("author_id", "merged_by_id", "resolved_by_id", "created_by_id");

    private static final SourceKind ISSUE_CORE = new SourceKind("scm.issue.core");
    private static final SourceKind PULL_REQUEST_CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind ISSUE_COMMENTS = new SourceKind("scm.issue.comments");
    private static final SourceKind GENERAL_REVIEW_COMMENTS = new SourceKind("scm.general-review-comments");
    private static final SourceKind INLINE_COMMENTS = new SourceKind("scm.pull-request.comments");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");

    private final ScmJobFolderRepository rows;
    private final JsonMapper mapper;
    private final PersonDataCopyRecorder personCopies;
    private final IssueCommentProvenance provenance;

    public ScmJobFolderProjector(
            ScmJobFolderRepository rows,
            JsonMapper mapper,
            PersonDataCopyRecorder personCopies,
            IssueCommentProvenance provenance) {
        this.rows = rows;
        this.mapper = mapper;
        this.personCopies = personCopies;
        this.provenance = provenance;
    }

    /**
     * One query per selected family for the whole repository, each read to its end before the next starts. Records
     * arrive family by family, each in work-number then row order. Every file holds the same lines in the same order
     * as when each piece of work was read on its own; only the order in which files are first written differs.
     */
    @Override
    public void forEachRecord(
            long workspace, long repository, Set<SourceKind> allowed, Consumer<ProjectedRecord> consumer) {
        each(rows.streamWork(workspace, repository), record -> {
            boolean pull = record.path("issue_type").asString().equals("PULL_REQUEST");
            SourceKind core = pull ? PULL_REQUEST_CORE : ISSUE_CORE;
            if (allowed.contains(core)) {
                String prefix = prefix(pull, record.path("number").asInt());
                consumer.accept(new ProjectedRecord(prefix + "record.json", core, Format.JSON, record));
                consumer.accept(new ProjectedRecord(prefix + "description.md", core, Format.MARKDOWN, record));
            }
        });
        if (allowed.contains(ISSUE_COMMENTS)) {
            Map<Long, Set<Long>> delivered = provenance.deliveredIdsByIssue(workspace, repository);
            parts(rows.streamIssueComments(workspace, repository, false), (part, comment) -> {
                if (!delivered
                        .getOrDefault(part.getIssueId(), Set.of())
                        .contains(comment.path("native_id").asLong())) {
                    consumer.accept(new ProjectedRecord(
                            prefix(false, part.getNumber()) + "comments.jsonl", ISSUE_COMMENTS, Format.JSONL, comment));
                }
            });
        }
        if (allowed.contains(GENERAL_REVIEW_COMMENTS)) {
            comments(
                    rows.streamIssueComments(workspace, repository, true),
                    "comments.jsonl",
                    GENERAL_REVIEW_COMMENTS,
                    consumer);
        }
        if (allowed.contains(INLINE_COMMENTS)) {
            comments(
                    rows.streamReviewComments(workspace, repository),
                    "inline-comments.jsonl",
                    INLINE_COMMENTS,
                    consumer);
        }
        if (allowed.contains(GENERAL_REVIEW_COMMENTS)) {
            comments(rows.streamReviews(workspace, repository), "reviews.jsonl", GENERAL_REVIEW_COMMENTS, consumer);
        }
        if (allowed.contains(THREADS)) {
            comments(rows.streamReviewThreads(workspace, repository), "threads.jsonl", THREADS, consumer);
        }
    }

    private static String prefix(boolean pull, int number) {
        return (pull ? "pulls/" : "issues/") + number + "/";
    }

    /** Hephaestus's own notes are not part of the work under review, so they never enter the folder. */
    private void comments(
            Stream<ScmJobFolderRepository.WorkPart> comments,
            String file,
            SourceKind kind,
            Consumer<ProjectedRecord> consumer) {
        parts(comments, (part, comment) -> {
            if (!comment.path("body").asString("").contains(HEPHAESTUS_MARKER)) {
                consumer.accept(
                        new ProjectedRecord(prefix(true, part.getNumber()) + file, kind, Format.JSONL, comment));
            }
        });
    }

    @Override
    public void forEachInventoryRecord(
            long workspace, long repository, String issueType, Consumer<ObjectNode> consumer) {
        each(rows.streamInventory(workspace, repository, issueType), consumer);
    }

    private void each(Stream<String> json, Consumer<ObjectNode> consumer) {
        try (json) {
            json.forEach(row -> consumer.accept(object(row)));
        }
    }

    private void parts(
            Stream<ScmJobFolderRepository.WorkPart> parts,
            BiConsumer<ScmJobFolderRepository.WorkPart, ObjectNode> consumer) {
        try (parts) {
            parts.forEach(part -> consumer.accept(part, object(part.getJson())));
        }
    }

    /** Every row read is recorded as a copy of the people it names before it is used. */
    private ObjectNode object(String row) {
        JsonNode value = mapper.readTree(row);
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalStateException("Workspace projection must be an object");
        }
        for (String actor : PERSON_FIELDS) {
            JsonNode id = object.path(actor);
            if (id.isIntegralNumber() && id.asLong() > 0) {
                personCopies.recordUser(id.asLong());
            }
        }
        return object;
    }
}
