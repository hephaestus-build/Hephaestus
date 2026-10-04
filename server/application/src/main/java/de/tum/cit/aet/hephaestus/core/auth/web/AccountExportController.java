package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportService;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportCreatedDTO;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportStatusDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GDPR Art. 20 self-service data export under {@code /user/exports}. Asynchronous: POST starts a
 * job (202 + Location), the client polls status, then downloads the JSON bundle once READY.
 *
 * <p>Thin HTTP adapter — all logic + ownership checks live in {@link AccountExportService}. The
 * authenticated account is resolved from the JWT {@code sub} via {@link CurrentAccount}; every
 * read is scoped to that account, and a foreign export id yields 404 (never 403) to avoid
 * enumeration.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/user/exports")
@Tag(name = "Account", description = "GDPR Art. 20 self-service data export")
@PreAuthorize("isAuthenticated()")
public class AccountExportController {

    private final AccountExportService exportService;

    public AccountExportController(AccountExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    @Operation(summary = "Request a data export (async)", operationId = "requestDataExport")
    public ResponseEntity<ExportCreatedDTO> requestExport() {
        Long accountId = CurrentAccount.requireId();
        AccountExport export = exportService.requestExport(accountId);
        URI location = URI.create("/user/exports/" + Objects.requireNonNull(export.getId()));
        return ResponseEntity.accepted()
                .location(location)
                .body(new ExportCreatedDTO(
                        Objects.requireNonNull(export.getId()),
                        export.getStatus().name()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get data-export status", operationId = "getDataExportStatus")
    public ResponseEntity<ExportStatusDTO> status(@PathVariable Long id) {
        Long accountId = CurrentAccount.requireId();
        ExportStatusDTO status = exportService
                .status(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The export was not found."));
        return ResponseEntity.ok(status);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download the data-export bundle", operationId = "downloadDataExport")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Long accountId = CurrentAccount.requireId();
        byte[] payload = exportService
                .downloadPayload(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The export is not available."));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("hephaestus-export-" + id + ".json")
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(payload);
    }
}
