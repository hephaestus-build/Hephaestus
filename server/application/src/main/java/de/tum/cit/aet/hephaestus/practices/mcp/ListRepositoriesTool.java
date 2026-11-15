package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.ItemController;
import de.tum.cit.aet.hephaestus.practices.groupdetail.ProductItemDirectoryQuery;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code list_repositories}: the workspace's repositories, as the review scope editor reads them through {@code ItemController.list}. */
@Component
@McpToolFor(controller = ItemController.class, method = "list")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ListRepositoriesTool implements McpTool {

    private static final int PAGE_SIZE = 100;

    private static final String INPUT_SCHEMA = """
            {"type": "object", "additionalProperties": false,
             "properties": {"page": {"type": "integer", "minimum": 0, "description": "Zero-based page of 100 repositories"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["repositories", "page", "hasMore"], "additionalProperties": false,
             "properties": {"page": {"type": "integer"}, "hasMore": {"type": "boolean"},
               "repositories": {"type": "array", "items": {"type": "object", "required": ["repositoryItemId", "provider", "name", "removedAt"],
                 "additionalProperties": false,
                 "properties": {"repositoryItemId": {"type": "string"}, "provider": {"type": ["string", "null"]},
                                "name": {"type": "string", "description": "owner/name"}, "removedAt": {"type": ["string", "null"]}}}}}}
            """;

    private final ProductItemDirectoryQuery directory;
    private final McpSchema.Tool definition;

    ListRepositoriesTool(ProductItemDirectoryQuery directory, JsonMapper jsonMapper) {
        this.directory = directory;
        this.definition = McpTools.readTool(
                jsonMapper,
                "list_repositories",
                "List repositories",
                "Lists the repositories of this workspace by item id, for choosing which ones Hephaestus reviews.",
                INPUT_SCHEMA,
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        int page = Math.max(0, McpTools.optionalInt(arguments, "page", 0));
        Page<ItemRefDTO> items = directory.list(
                WorkspaceContextHolder.getContext().id(), "scm.repository", PageRequest.of(page, PAGE_SIZE));
        List<Repository> rows = items.getContent().stream()
                .map(item -> new Repository(
                        item.id(),
                        item.provider() == null ? null : item.provider().name(),
                        item.label(),
                        McpProjections.time(item.removedAt())))
                .toList();
        return McpTools.result(new Result(rows, page, items.hasNext()));
    }

    record Result(List<Repository> repositories, int page, boolean hasMore) {}

    record Repository(String repositoryItemId, @Nullable String provider, String name, @Nullable String removedAt) {}
}
