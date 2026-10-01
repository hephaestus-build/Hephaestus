package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ScmJobFolderProjector implements WorkspaceScmProjection {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public ScmJobFolderProjector(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void forEachRecord(
            long workspace, long repository, Set<SourceKind> allowed, java.util.function.Consumer<Record> consumer) {
        rows("""
            SELECT (to_jsonb(i)||jsonb_build_object('synced_at',i.last_sync_at))::text FROM issue i
            WHERE i.repository_id=? AND i.deleted_at IS NULL AND EXISTS
              (SELECT 1 FROM repository_to_monitor m JOIN repository r ON r.name_with_owner=m.name_with_owner
               WHERE m.workspace_id=? AND r.id=i.repository_id) ORDER BY i.number
            """, new Object[] {repository, workspace}, record -> {
            boolean pull = record.path("issue_type").asString().equals("PULL_REQUEST");
            String prefix =
                    (pull ? "pulls/" : "issues/") + record.path("number").asInt() + "/";
            String kind = pull ? "scm.pull-request.core" : "scm.issue.core";
            if (allowed.contains(new SourceKind(kind))) {
                consumer.accept(new Record(prefix + "record.json", new SourceKind(kind), Format.JSON, record));
                consumer.accept(new Record(prefix + "description.md", new SourceKind(kind), Format.MARKDOWN, record));
            }
            long issue = record.path("id").asLong();
            comments(
                    prefix,
                    "comments.jsonl",
                    "issue_comment",
                    issue,
                    workspace,
                    allowed,
                    pull ? "scm.general-review-comments" : "scm.issue.comments",
                    consumer);
            if (pull) {
                comments(
                        prefix,
                        "inline-comments.jsonl",
                        "pull_request_review_comment",
                        issue,
                        workspace,
                        allowed,
                        "scm.pull-request.comments",
                        consumer);
                comments(
                        prefix,
                        "reviews.jsonl",
                        "pull_request_review",
                        issue,
                        workspace,
                        allowed,
                        "scm.general-review-comments",
                        consumer);
                comments(
                        prefix,
                        "threads.jsonl",
                        "pull_request_review_thread",
                        issue,
                        workspace,
                        allowed,
                        "scm.review-threads",
                        consumer);
            }
        });
    }

    private void comments(
            String prefix,
            String filename,
            String table,
            long issue,
            long workspace,
            Set<SourceKind> allowed,
            String kind,
            java.util.function.Consumer<Record> consumer) {
        if (!allowed.contains(new SourceKind(kind))) return;
        String foreign = table.equals("issue_comment") ? "issue_id" : "pull_request_id";
        rows(
                "SELECT (to_jsonb(c)||jsonb_build_object('synced_at',NULL))::text FROM " + table
                        + " c JOIN issue i ON i.id=c." + foreign
                        + " WHERE i.id=? AND EXISTS (SELECT 1 FROM repository_to_monitor m JOIN repository r ON r.name_with_owner=m.name_with_owner WHERE m.workspace_id=? AND r.id=i.repository_id) ORDER BY c.id",
                new Object[] {issue, workspace},
                p -> {
                    String body = p.path("body").asString("");
                    if (!body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER))
                        consumer.accept(new Record(prefix + filename, new SourceKind(kind), Format.JSONL, p));
                });
    }

    @Override
    public void forEachInventoryRecord(
            long workspace, long repository, String issueType, java.util.function.Consumer<ObjectNode> consumer) {
        rows("""
            SELECT jsonb_build_object('id',i.id,'number',i.number,'title',i.title,'state',i.state,
              'url',i.html_url,'synced_at',i.last_sync_at,'repository',r.name_with_owner)::text
            FROM issue i JOIN repository r ON r.id=i.repository_id
            WHERE i.repository_id=? AND i.issue_type=? AND i.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM repository_to_monitor m WHERE m.workspace_id=? AND m.name_with_owner=r.name_with_owner)
            ORDER BY i.number
            """, new Object[] {repository, issueType, workspace}, consumer);
    }

    private void rows(String sql, Object[] args, java.util.function.Consumer<ObjectNode> consumer) {
        jdbc.query(
                connection -> {
                    var statement = connection.prepareStatement(sql);
                    statement.setFetchSize(256);
                    for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
                    return statement;
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonNode value = mapper.readTree(rs.getString(1));
                    if (!(value instanceof ObjectNode object))
                        throw new IllegalStateException("Workspace projection must be an object");
                    consumer.accept(object);
                });
    }
}
