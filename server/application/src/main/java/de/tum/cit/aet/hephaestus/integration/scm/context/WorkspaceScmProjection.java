package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

/** Integration-owned streaming reads; the folder renderer never reads provider tables itself. */
public interface WorkspaceScmProjection {
    String HEPHAESTUS_MARKER = "<!-- hephaestus";

    record Record(String path, SourceKind kind, Format format, ObjectNode value) {}

    enum Format {
        JSON,
        JSONL,
        MARKDOWN
    }

    void forEachRecord(long workspaceId, long repositoryId, Set<SourceKind> allowed, Consumer<Record> consumer);

    void forEachInventoryRecord(long workspaceId, long repositoryId, String issueType, Consumer<ObjectNode> consumer);
}
