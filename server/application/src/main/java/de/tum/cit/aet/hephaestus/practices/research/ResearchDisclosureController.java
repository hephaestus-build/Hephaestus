package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.PeopleCount;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosure.Closure;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureSubject.RemovalReason;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Instance-wide research disclosure; every operation checks the operator's deployment assurance policy. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/research-disclosures")
@RequireInstanceAdmin
@RecentSignInExempt(reason = "Every entry enforces deployment assurance through AdminPasskeyAccess.requireFresh")
@Tag(name = "Research", description = "Instance-admin research disclosures and downstream removal receipts")
public class ResearchDisclosureController {

    private final ResearchDisclosureService service;
    private final AdminPasskeyAccess passkeys;
    private final WorkspaceContextResolver workspaces;

    public ResearchDisclosureController(
            ResearchDisclosureService service, AdminPasskeyAccess passkeys, WorkspaceContextResolver workspaces) {
        this.service = service;
        this.passkeys = passkeys;
        this.workspaces = workspaces;
    }

    public record ReleaseResearchDatasetDTO(
            @NonNull @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
            String workspaceId,

            @NonNull @NotNull LocalDate firstDay,
            @NonNull @NotNull LocalDate lastDay,
            @NonNull @NotNull Instant useUntil) {}

    public record RecordResearchRemovalsDTO(
            @NonNull @NotEmpty List<@NotBlank String> participantKeys) {}

    public record CloseResearchDisclosureDTO(
            @NonNull @NotNull Closure closure) {}

    public record ResearchRemovalObligationDTO(
            @NonNull String participantKey,
            @NonNull RemovalReason reason,
            @NonNull Instant requiredAt) {}

    public record ResearchRemovalDisclosureDTO(
            @NonNull String datasetId,
            @NonNull String workspaceId,
            @NonNull Instant createdAt,
            @NonNull Instant useUntil,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean useExpired,

            @NonNull List<ResearchRemovalObligationDTO> removals) {}

    public record ResearchRemovalReceiptDTO(
            @PeopleCount(exactBecause = "Participant keys that the caller just submitted")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int recorded) {}

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "releaseResearchDataset",
            summary = "Release and download one consented research dataset",
            description = "Records a disclosure and returns its JSON file once. The file is pseudonymous personal"
                    + " data. It is not stored for redownload. The configured research question and fixed dataset"
                    + " purpose apply; the request cannot override them.")
    @ApiResponse(
            responseCode = "200",
            description = "Research dataset JSON attachment",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "RESEARCH_DATASET_RELEASED")
    public ResponseEntity<byte[]> release(@Valid @RequestBody ReleaseResearchDatasetDTO request) {
        long admin = requireActor();
        long workspaceId = parseWorkspaceId(request.workspaceId());
        workspaces.requireWorkspace(workspaceId);
        var released = service.release(workspaceId, request.firstDay(), request.lastDay(), request.useUntil(), admin);
        String disposition = ContentDisposition.attachment()
                .filename("hephaestus-research-" + released.datasetId() + ".json")
                .build()
                .toString();
        // The service's authorization/audit transaction has committed before HTTP writes these bytes.
        return noStore(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(released.content());
    }

    @PostMapping("/removal-checks")
    @Operation(
            operationId = "checkResearchRemovals",
            summary = "Check and record research removal obligations",
            description = "Records newly detected obligations and returns every open disclosure, including those"
                    + " with no removals owed. Closed disclosures are excluded.")
    @AuditExempt(reason = "Records consent-derived obligations; creates no dataset or external-removal attestation")
    public ResponseEntity<List<ResearchRemovalDisclosureDTO>> checkRemovals() {
        requireActor();
        var manifest = service.removalManifest().stream()
                .map(disclosure -> new ResearchRemovalDisclosureDTO(
                        disclosure.datasetId().toString(),
                        Long.toString(disclosure.workspaceId()),
                        disclosure.createdAt(),
                        disclosure.useUntil(),
                        disclosure.useExpired(),
                        disclosure.removals().stream()
                                .map(obligation -> new ResearchRemovalObligationDTO(
                                        obligation.participantKey(), obligation.reason(), obligation.requiredAt()))
                                .toList()))
                .toList();
        return noStore(HttpStatus.OK).body(manifest);
    }

    @PostMapping("/{datasetId}/removal-receipts")
    @Operation(
            operationId = "recordResearchRemovals",
            summary = "Attest removal of participant keys from retained research copies",
            description = "Records the operator's statement. Hephaestus cannot inspect or revoke downloaded copies.")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "RESEARCH_REMOVAL_RECORDED")
    public ResponseEntity<ResearchRemovalReceiptDTO> recordRemoved(
            @PathVariable UUID datasetId, @Valid @RequestBody RecordResearchRemovalsDTO request) {
        long admin = requireActor();
        int recorded = service.recordRemoved(datasetId, request.participantKeys(), admin);
        return noStore(HttpStatus.OK).body(new ResearchRemovalReceiptDTO(recorded));
    }

    @PostMapping("/{datasetId}/closure")
    @Operation(
            operationId = "closeResearchDisclosure",
            summary = "Attest destruction or anonymization of every retained research copy",
            description = "Closes the disclosure and deletes its protected subject mapping. This is an operator"
                    + " attestation, not a check of the research team's storage.")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "RESEARCH_REMOVAL_RECORDED")
    public ResponseEntity<Void> close(
            @PathVariable UUID datasetId, @Valid @RequestBody CloseResearchDisclosureDTO request) {
        long admin = requireActor();
        service.close(datasetId, request.closure(), admin);
        return noStore(HttpStatus.NO_CONTENT).build();
    }

    private long requireActor() {
        passkeys.requireFresh();
        return CurrentAccount.requireId();
    }

    private static long parseWorkspaceId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The workspace ID is not valid.", invalid);
        }
    }

    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache");
    }
}
