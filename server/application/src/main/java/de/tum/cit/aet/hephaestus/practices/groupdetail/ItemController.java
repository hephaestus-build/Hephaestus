package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.practices.profile.OwnReviewFilter;
import de.tum.cit.aet.hephaestus.practices.profile.PracticeProfileReviewService;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemFactDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemFactsDTO;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceQueryService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/items")
@RequiredArgsConstructor
public class ItemController {
    private final ProductItemDirectoryQuery directory;
    private final ProductItemQuery items;
    private final ItemTraceQueryService trace;
    private final PracticeProfileReviewService reviews;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listReviewItems", summary = "List items in the workspace")
    public PageResponseDTO<ItemRefDTO> list(
            WorkspaceContext ws,
            @RequestParam(required = false) @Nullable String itemKind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponseDTO.from(directory.list(ws.id(), itemKind, PageRequest.of(page, size)));
    }

    @GetMapping("/legacy-feedback/{artifactKind}/{artifactId}")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "resolveLegacyFeedbackItem", summary = "Resolve a feedback link posted before item IDs")
    public LegacyFeedbackItemDTO legacyFeedback(
            WorkspaceContext ws,
            @PathVariable String artifactKind,
            @Parameter(schema = @Schema(type = "string", pattern = "^[1-9][0-9]*$", maxLength = 19)) @PathVariable
                    long artifactId) {
        return directory.legacyFeedback(ws.id(), artifactKind, artifactId);
    }

    @GetMapping("/{itemId}")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getReviewItem", summary = "Get an item")
    public ItemRefDTO get(WorkspaceContext ws, @PathVariable long itemId) {
        return trace.requireItem(ws.id(), itemId);
    }

    @GetMapping("/{itemId}/facts")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getItemFacts", summary = "Get current facts about an item")
    public ItemFactsDTO facts(WorkspaceContext ws, @PathVariable long itemId) {
        var item = items.require(ws.id(), itemId);
        Map<String, String> state;
        try {
            state = items.current(item)
                    .orElseThrow(() -> new EntityNotFoundException("Item", Long.toString(itemId)))
                    .state();
        } catch (ItemCaptureException unavailable) {
            if (unavailable.code() != ItemCaptureCode.VERSION_UNAVAILABLE) throw unavailable;
            // Retained items can lack the provider revision needed to establish current facts.
            return new ItemFactsDTO(trace.requireItem(ws.id(), itemId), List.of());
        }
        return new ItemFactsDTO(
                trace.requireItem(ws.id(), itemId),
                state.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new ItemFactDTO(e.getKey(), e.getValue(), null))
                        .toList());
    }

    @GetMapping("/{itemId}/events")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listItemEvents", summary = "List item events")
    public PageResponseDTO<ItemEventDTO> events(
            WorkspaceContext ws,
            @PathVariable long itemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponseDTO.from(trace.events(ws.id(), itemId, PageRequest.of(page, size)));
    }

    @GetMapping("/{itemId}/reviews")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "listOwnItemReviews", summary = "List your reviews of an item")
    public PageResponseDTO<ProfileReviewDTO> reviews(
            WorkspaceContext ws,
            @PathVariable long itemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponseDTO.from(reviews.list(
                ws, new OwnReviewFilter(null, itemId, null, null, null, false), PageRequest.of(page, size)));
    }
}
