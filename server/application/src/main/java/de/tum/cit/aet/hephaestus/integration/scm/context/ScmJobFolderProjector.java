package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Set;
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
    private final ScmJobFolderRepository rows;
    private final JsonMapper mapper;

    public ScmJobFolderProjector(ScmJobFolderRepository rows, JsonMapper mapper) {
        this.rows = rows;
        this.mapper = mapper;
    }

    @Override
    public void forEachRecord(
            long workspace, long repository, Set<SourceKind> allowed, Consumer<ProjectedRecord> consumer) {
        each(rows.streamWork(workspace, repository), record -> {
            boolean pull = record.path("issue_type").asString().equals("PULL_REQUEST");
            String prefix =
                    (pull ? "pulls/" : "issues/") + record.path("number").asInt() + "/";
            SourceKind core = new SourceKind(pull ? "scm.pull-request.core" : "scm.issue.core");
            if (allowed.contains(core)) {
                consumer.accept(new ProjectedRecord(prefix + "record.json", core, Format.JSON, record));
                consumer.accept(new ProjectedRecord(prefix + "description.md", core, Format.MARKDOWN, record));
            }
            long issue = record.path("id").asLong();
            SourceKind generalComments = new SourceKind(pull ? "scm.general-review-comments" : "scm.issue.comments");
            if (allowed.contains(generalComments)) {
                comments(
                        rows.streamIssueComments(workspace, issue),
                        prefix + "comments.jsonl",
                        generalComments,
                        consumer);
            }
            if (!pull) {
                return;
            }
            SourceKind inlineComments = new SourceKind("scm.pull-request.comments");
            if (allowed.contains(inlineComments)) {
                comments(
                        rows.streamReviewComments(workspace, issue),
                        prefix + "inline-comments.jsonl",
                        inlineComments,
                        consumer);
            }
            if (allowed.contains(generalComments)) {
                comments(rows.streamReviews(workspace, issue), prefix + "reviews.jsonl", generalComments, consumer);
            }
            SourceKind threads = new SourceKind("scm.review-threads");
            if (allowed.contains(threads)) {
                comments(rows.streamReviewThreads(workspace, issue), prefix + "threads.jsonl", threads, consumer);
            }
        });
    }

    /** Hephaestus's own notes are not part of the work under review, so they never enter the folder. */
    private void comments(Stream<String> comments, String path, SourceKind kind, Consumer<ProjectedRecord> consumer) {
        each(comments, comment -> {
            if (!comment.path("body").asString("").contains(HEPHAESTUS_MARKER)) {
                consumer.accept(new ProjectedRecord(path, kind, Format.JSONL, comment));
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
            json.forEach(row -> {
                JsonNode value = mapper.readTree(row);
                if (!(value instanceof ObjectNode object)) {
                    throw new IllegalStateException("Workspace projection must be an object");
                }
                consumer.accept(object);
            });
        }
    }
}
