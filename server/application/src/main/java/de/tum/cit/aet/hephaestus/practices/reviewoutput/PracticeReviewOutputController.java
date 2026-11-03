package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.CreateResultMarkRequestDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.RemoveResultMarkRequestDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ResultMarkDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewLineageDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewProblemDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewProblemDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewResultDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewResultDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/reviews")
@RequireAtLeastWorkspaceAdmin
@RequiredArgsConstructor
@Validated
public class PracticeReviewOutputController {
    private final ReviewResultQueryService query;
    private final ResultMarkService marks;
    private final ResultPresentation presentation;
    private final ReviewLineageService lineage;

    @GetMapping("/results")
    @Operation(operationId = "listReviewResults", summary = "List review results")
    public OffsetPageDTO<ReviewResultDTO> list(
            WorkspaceContext ws,
            @Valid @ParameterObject ReviewResultFilterParams filter,
            @RequestParam(defaultValue = "NEWEST") ResultSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return OffsetPageDTO.from(query.list(ws.id(), filter.toFilter(), sort, PageRequest.of(page, size)));
    }

    @GetMapping("/results/{resultId}")
    @Operation(operationId = "getReviewResult", summary = "Get a review result")
    public ReviewResultDetailDTO result(WorkspaceContext ws, @PathVariable UUID resultId) {
        return query.get(ws.id(), resultId);
    }

    @GetMapping("/problems")
    @Operation(
            operationId = "listReviewProblems",
            summary = "List review problems",
            description = "Problems are ordered by when their result was recorded, newest first unless sort is OLDEST.")
    public OffsetPageDTO<ReviewProblemDTO> problems(
            WorkspaceContext ws,
            @Valid @ParameterObject ReviewProblemFilterParams filter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return OffsetPageDTO.from(query.listProblems(
                ws.id(), filter.toResultFilter(), filter.toProblemFilter(), PageRequest.of(page, size)));
    }

    @GetMapping("/problems/{problemId}")
    @Operation(operationId = "getReviewProblem", summary = "Get a review problem")
    public ReviewProblemDetailDTO problem(WorkspaceContext ws, @PathVariable UUID problemId) {
        return query.problem(ws.id(), problemId);
    }

    @GetMapping("/results/{resultId}/marks")
    @Operation(operationId = "listResultMarks", summary = "List the marks on a result")
    public List<ResultMarkDTO> marks(WorkspaceContext ws, @PathVariable UUID resultId) {
        return marks.list(ws.id(), resultId).stream()
                .map(m -> presentation.mark(ws.id(), m))
                .toList();
    }

    @PostMapping("/results/{resultId}/marks")
    @AuditExempt(reason = "The result mark records the actor and reason")
    @Operation(operationId = "createResultMark", summary = "Mark a result")
    @ApiResponse(responseCode = "201", description = "Created")
    public ResponseEntity<ResultMarkDTO> createMark(
            WorkspaceContext ws, @PathVariable UUID resultId, @Valid @RequestBody CreateResultMarkRequestDTO request) {
        var m = marks.create(
                ws.id(),
                resultId,
                request.kind(),
                SecurityUtils.getCurrentAccountId().orElseThrow(),
                request.reason());
        return ResponseEntity.status(201).body(presentation.mark(ws.id(), m));
    }

    @DeleteMapping("/results/{resultId}/marks/{markId}")
    @AuditExempt(reason = "The result mark records removal, actor and reason")
    @Operation(operationId = "removeResultMark", summary = "Remove a result mark")
    public ResultMarkDTO removeMark(
            WorkspaceContext ws,
            @PathVariable UUID resultId,
            @PathVariable UUID markId,
            @Valid @RequestBody RemoveResultMarkRequestDTO request) {
        return presentation.mark(
                ws.id(),
                marks.remove(
                        ws.id(),
                        resultId,
                        markId,
                        SecurityUtils.getCurrentAccountId().orElseThrow(),
                        request.reason()));
    }

    @GetMapping("/lineage")
    @Operation(operationId = "getReviewLineage", summary = "Trace an item, review, result, problem or feedback")
    public ReviewLineageDTO lineage(WorkspaceContext ws, @Valid @ParameterObject ReviewLineageFilterParams filter) {
        return lineage.graph(ws.id(), filter);
    }
}
