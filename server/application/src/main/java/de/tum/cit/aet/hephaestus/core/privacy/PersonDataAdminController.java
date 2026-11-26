package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest.State;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/person-data")
@RequireInstanceAdmin
@Tag(name = "Person Data", description = "Instance-administrator access and erasure requests")
@RequiredArgsConstructor
public class PersonDataAdminController {
    private final PersonDataService service;
    private final PersonIdentityResolver resolver;
    private final ObjectMapper mapper;
    private final AuthEventLogger audit;

    public record PersonDataProviderDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long id,
            @NonNull String type,
            @NonNull String serverUrl) {}

    @GetMapping("/providers")
    @Operation(
            operationId = "adminListPersonDataProviders",
            summary = "List exact provider instances without credentials")
    public ResponseEntity<List<PersonDataProviderDTO>> providers() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(resolver.providers().stream()
                        .map(p -> new PersonDataProviderDTO(p.id(), p.type(), p.serverUrl()))
                        .toList());
    }

    public record IdentityDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) @Positive
            long providerId,

            @NonNull @Size(min = 1, max = 255) String subject,
            @Nullable @Size(min = 1, max = 255) String teamId) {
        PersonIdentity identity() {
            return new PersonIdentity(providerId, subject, teamId);
        }
    }

    public record PreviewRequestDTO(
            @Nullable @Positive Long accountId,
            @NonNull @NotNull @Size(max = 32) List<@Valid IdentityDTO> identities) {}

    public record ErasureRequestDTO(@NonNull @NotNull Boolean externalCopiesRemoved) {}

    public record ExternalDeliveryDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long workspaceId,
            @NonNull String locator) {}

    public record PersonDataScopeDTO(
            @Nullable Long accountId, @NonNull List<IdentityDTO> identities) {}

    public record PersonDataRequestDTO(
            @NonNull UUID id,
            @NonNull State state,
            @NonNull Instant expiresAt,
            @NonNull Map<String, Long> counts,
            @NonNull Map<String, Long> completed,
            @NonNull List<ExternalDeliveryDTO> externalDeliveries,
            @Nullable String failureCode,
            @Nullable PersonDataScopeDTO scope) {}

    @PostMapping("/preview")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PERSON_DATA_PREVIEWED")
    @Operation(
            operationId = "adminPreviewPersonData",
            summary = "Resolve exact identities and preview every personal-data store")
    public ResponseEntity<PersonDataRequestDTO> preview(@Valid @RequestBody PreviewRequestDTO body) {
        var snapshot = service.preview(
                CurrentAccount.requireId(),
                body.accountId(),
                body.identities().stream().map(IdentityDTO::identity).toList());
        record(AuthEvent.EventType.PERSON_DATA_PREVIEWED);
        return response(snapshot);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "adminGetPersonDataRequest", summary = "Get a person-data preview or erasure receipt")
    public ResponseEntity<PersonDataRequestDTO> get(@PathVariable UUID id) {
        return response(service.get(id));
    }

    @PostMapping("/{id}/erase")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PERSON_DATA_ERASURE_REQUESTED")
    @Operation(operationId = "adminErasePersonData", summary = "Start or resume the preview's audited erasure job")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PersonDataRequestDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The preview changed or a copy is in progress",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The erasure is on hold and its journal copy is pending (journal-pending)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PersonDataRequestDTO> erase(
            @PathVariable UUID id, @Valid @RequestBody ErasureRequestDTO body) {
        service.requestErasure(id, CurrentAccount.requireId(), body.externalCopiesRemoved());
        record(AuthEvent.EventType.PERSON_DATA_ERASURE_REQUESTED);
        return response(service.get(id));
    }

    private void record(AuthEvent.EventType type) {
        if (!audit.event(type, AuthEvent.Result.SUCCESS)
                .actingAccount(CurrentAccount.requireId())
                .record())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The audit trail is unavailable. Try again in a moment.");
    }

    private ResponseEntity<PersonDataRequestDTO> response(PersonDataService.Snapshot snapshot) {
        var r = snapshot.request();
        Map<String, Long> counts = mapper.readValue(r.getCountsJson(), new TypeReference<Map<String, Long>>() {});
        Map<String, Long> completed = PersonDataStoreReceipt.counts(mapper, r.getCompletedJson());
        PersonDataScopeDTO resolvedScope = null;
        if (r.getScopeJson() != null) {
            var scope = mapper.readValue(r.getScopeJson(), PersonScope.class);
            resolvedScope = new PersonDataScopeDTO(
                    scope.accountId(),
                    scope.identities().stream()
                            .map(identity ->
                                    new IdentityDTO(identity.providerId(), identity.subject(), identity.teamId()))
                            .toList());
        }
        var locations = snapshot.externalDeliveries().stream()
                .map(d -> new ExternalDeliveryDTO(d.workspaceId(), d.locator()))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PersonDataRequestDTO(
                        r.getId(),
                        r.getState(),
                        r.getExpiresAt(),
                        counts,
                        completed,
                        locations,
                        r.getFailureCode(),
                        resolvedScope));
    }
}
