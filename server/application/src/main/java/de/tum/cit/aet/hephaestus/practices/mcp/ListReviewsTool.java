package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewFilterParams;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryController;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code list_reviews}: the newest reviews and where each stands, as {@code ReviewSummaryController.listReviews}
 * reads them. No title, no outcome and no feedback count goes out (ADR 0110 § Data contract).
 */
@Component
@McpToolFor(controller = ReviewSummaryController.class, method = "listReviews")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ListReviewsTool implements McpTool {

    private static final int PAGE_SIZE = 25;

    private static final String INPUT_SCHEMA = """
            {"type": "object", "additionalProperties": false,
             "properties": {"page": {"type": "integer", "minimum": 0, "description": "Zero-based page of 25 reviews, newest first"}}}
            """;

    static final String REVIEW_SCHEMA =
            """
            {"type": "object", "required": ["id", "kind", "origin", "status", "createdAt", "completedAt", "stopReason", "item", "practices"],
             "additionalProperties": false,
             "properties": {"id": {"type": "string"}, "kind": {"type": "string"}, "origin": {"type": "string"}, "status": {"type": "string"},
                 "createdAt": {"type": "string"}, "completedAt": {"type": ["string", "null"]}, "stopReason": {"type": ["string", "null"]},
                 "item": %s, "practices": {"type": "array", "items": %s}}}
            """.formatted(McpProjections.ITEM_REFERENCE_SCHEMA, McpProjections.PRACTICE_STATUS_SCHEMA);

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["reviews", "page", "hasMore"], "additionalProperties": false,
             "properties": {"page": {"type": "integer"}, "hasMore": {"type": "boolean"}, "reviews": {"type": "array", "items": %s}}}
            """.formatted(REVIEW_SCHEMA);

    private final ReviewSummaryQueryService reviews;
    private final McpSchema.Tool definition;

    ListReviewsTool(ReviewSummaryQueryService reviews, JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.definition = McpTools.readTool(
                jsonMapper,
                "list_reviews",
                "List reviews",
                "Lists the newest practice reviews of this workspace with their status and, for each practice, its"
                        + " status and reason code. It names work by repository and number only.",
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
        var filter = new ReviewFilterParams(null, null, null, null, null, null, null, null, null, null);
        Page<ReviewSummaryDTO> found = reviews.list(
                WorkspaceContextHolder.getContext().id(),
                filter.validated(),
                PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return McpTools.result(
                new Result(found.getContent().stream().map(ListReviewsTool::row).toList(), page, found.hasNext()));
    }

    static Review row(ReviewSummaryDTO review) {
        return new Review(
                review.id().toString(),
                review.kind().name(),
                review.origin().name(),
                review.status().name(),
                review.createdAt().toString(),
                McpProjections.time(review.completedAt()),
                review.stopReason() == null ? null : review.stopReason().name(),
                McpProjections.ItemReference.of(review.item()),
                McpProjections.practices(review.practices()));
    }

    record Result(List<Review> reviews, int page, boolean hasMore) {}

    record Review(
            String id,
            String kind,
            String origin,
            String status,
            String createdAt,
            @Nullable String completedAt,
            @Nullable String stopReason,
            McpProjections.ItemReference item,
            List<McpProjections.PracticeStatus> practices) {}
}
