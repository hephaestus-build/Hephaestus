package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryController;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDetailDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** {@code get_review}: where one review stands, as {@code ReviewSummaryController.get} reads it. */
@Component
@McpToolFor(controller = ReviewSummaryController.class, method = "get")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class GetReviewTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["reviewId"], "additionalProperties": false,
             "properties": {"reviewId": {"type": "string", "format": "uuid"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["id", "kind", "origin", "status", "createdAt", "completedAt", "stopReason", "item", "practices", "jobCount", "activeJobCount"],
             "additionalProperties": false,
             "properties": {"id": {"type": "string"}, "kind": {"type": "string"}, "origin": {"type": "string"}, "status": {"type": "string"},
                 "createdAt": {"type": "string"}, "completedAt": {"type": ["string", "null"]}, "stopReason": {"type": ["string", "null"]},
                 "item": %s, "practices": {"type": "array", "items": %s}, "jobCount": {"type": "integer"}, "activeJobCount": {"type": "integer"}}}
            """.formatted(McpProjections.ITEM_REFERENCE_SCHEMA, McpProjections.PRACTICE_STATUS_SCHEMA);

    private final ReviewSummaryQueryService reviews;
    private final McpSchema.Tool definition;

    GetReviewTool(ReviewSummaryQueryService reviews, JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.definition = McpTools.readTool(
                jsonMapper,
                "get_review",
                "Get a review",
                "Returns where one practice review stands: its status, its stop reason, and for each practice its"
                        + " status and reason code, such as a practice readiness reason. It never returns outcomes or feedback.",
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
        UUID reviewId;
        try {
            reviewId = UUID.fromString(McpTools.requiredString(arguments, "reviewId"));
        } catch (IllegalArgumentException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The argument reviewId is not a review id.");
        }
        ReviewDetailDTO review = reviews.detail(WorkspaceContextHolder.getContext().id(), reviewId);
        return McpTools.result(new Result(
                review.id().toString(),
                review.kind().name(),
                review.origin().name(),
                review.status().name(),
                review.createdAt().toString(),
                McpProjections.time(review.completedAt()),
                review.stopReason() == null ? null : review.stopReason().name(),
                McpProjections.ItemReference.of(review.item()),
                McpProjections.practices(review.practices()),
                review.jobCount(),
                review.activeJobCount()));
    }

    record Result(
            String id,
            String kind,
            String origin,
            String status,
            String createdAt,
            @Nullable String completedAt,
            @Nullable String stopReason,
            McpProjections.ItemReference item,
            List<McpProjections.PracticeStatus> practices,
            int jobCount,
            int activeJobCount) {}
}
