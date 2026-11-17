package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** A practice as a folder of files: download one, or check one before it is committed (ADR 0118). */
@WorkspaceScopedController
@RequestMapping("/practices/{practiceSlug}")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PracticeFileController {
    private final PracticeFileService files;

    @GetMapping(value = "/files", produces = "application/zip")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "downloadPracticeFiles", summary = "Download a practice as a folder of files")
    @ApiResponse(
            responseCode = "200",
            description = "A ZIP archive with the practice folder",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> download(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(practiceSlug + ".zip")
                                .build()
                                .toString())
                .body(files.zip(ctx, practiceSlug));
    }

    @PostMapping("/file-check")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "A check reads the files that the caller sends and saves nothing")
    @Operation(
            operationId = "checkPracticeFiles",
            summary = "Check a practice folder without saving it",
            description = "Parses and validates the files as the practice named in the path. Calls no model.")
    public PracticeFileCheckDTO check(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid PracticeFileCheckRequestDTO request) {
        return files.check(practiceSlug, request.files());
    }
}
