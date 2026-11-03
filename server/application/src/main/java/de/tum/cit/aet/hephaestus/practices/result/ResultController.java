package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultDetailDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

// Membership alone is insufficient; every endpoint must restrict reads to the authenticated developer.
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/results")
@Tag(name = "Practice Results", description = "Read-only access to practice evaluation results")
@RequiredArgsConstructor
@Validated
public class ResultController {

    private final ResultQueryService resultQueryService;

    @GetMapping
    @Operation(
            operationId = "listOwnResults",
            summary = "List results for current developer",
            description = "Paginated results for the authenticated developer with optional filters")
    @ApiResponse(responseCode = "200", description = "Paginated results returned")
    public ResponseEntity<OffsetPageDTO<ResultListDTO>> listResults(
            WorkspaceContext workspaceContext, @Valid @ParameterObject ResultFeedFilterParams filter) {
        Pageable pageable = filter.pageable();

        Page<ResultListDTO> practiceResults =
                resultQueryService.listOwnResults(workspaceContext.id(), filter.toQuery(), pageable);
        return ResponseEntity.ok(OffsetPageDTO.from(practiceResults));
    }

    @GetMapping("/{resultId}")
    @Operation(operationId = "getOwnResult", summary = "Get result detail")
    @ApiResponse(
            responseCode = "200",
            description = "Result detail returned",
            content = @Content(schema = @Schema(implementation = ResultDetailDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Result not found or not owned by current user",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<ResultDetailDTO> getPracticeResult(
            WorkspaceContext workspaceContext, @PathVariable UUID resultId) {
        return ResponseEntity.ok(resultQueryService.ownResult(workspaceContext.id(), resultId));
    }
}
