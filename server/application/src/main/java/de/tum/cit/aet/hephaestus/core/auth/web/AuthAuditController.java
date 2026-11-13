package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthAuditService;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.Csv;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.core.web.TimeIdCursor;
import de.tum.cit.aet.hephaestus.core.web.TotalCountDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnServerRole
@RestController
@RequestMapping("/admin/audit")
@Tag(name = "Admin")
@RequireInstanceAdmin
public class AuthAuditController {

    /** Hard cap on page size so a malicious/typo'd {@code size} can't scan a whole partition. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Upper bound on a single CSV export so the admin can't pull an unbounded slice of the log. */
    private static final int EXPORT_MAX_ROWS = 10_000;

    /** How many matching events the file leaves out, so that a client can say so. */
    private static final String EXPORT_LEFT_OUT_HEADER = "X-Left-Out-Count";

    private final AuthAuditService authAuditService;

    public AuthAuditController(AuthAuditService authAuditService) {
        this.authAuditService = authAuditService;
    }

    /**
     * A human-readable account identity. {@code displayName}/{@code email} are null for deleted
     * accounts.
     */
    public record AuthAuditAccountDTO(
            @NonNull Long id,
            @Nullable String displayName,
            @Nullable String email) {}

    public record AuditFilterParams(
            @RequestParam(required = false) @Nullable Long accountId,
            @RequestParam(required = false) @Nullable Long actingAccountId,
            @RequestParam(required = false) @Nullable List<AuthEvent.EventType> eventType,
            @RequestParam(required = false) @Nullable List<AuthEvent.Result> result,

            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
            Instant from,

            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
            Instant to) {
        AuthAuditService.Filter toFilter() {
            return new AuthAuditService.Filter(accountId, actingAccountId, eventType, result, from, to);
        }
    }

    /** One audit row, flattened for the admin viewer. */
    public record AuthEventViewDTO(
            @NonNull Long id,
            @NonNull Instant occurredAt,
            @NonNull String eventType,
            @NonNull String result,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean elevatedViaInstanceAdmin,
            @Nullable Long accountId,
            @Nullable Long actingAccountId,
            @Nullable Long viewedActorId,
            @Nullable Long viewedAccountId,
            @Nullable AuthAuditAccountDTO account,
            @Nullable AuthAuditAccountDTO actor,
            @Nullable String failureReason,
            @Nullable Long workspaceId,
            @Nullable String ipAddress,
            @Nullable String userAgent,
            @Nullable String details) {}

    @GetMapping
    @Operation(summary = "List auth audit events (newest first)", operationId = "adminListAuthEvents")
    public ResponseEntity<CursorPageDTO<AuthEventViewDTO>> list(
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int size,
            @ParameterObject AuditFilterParams filter) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        AuthAuditService.AuditPage read =
                authAuditService.list(filter.toFilter(), TimeIdCursor.decode(cursor), safeSize + 1);
        boolean more = read.events().size() > safeSize;
        List<AuthEvent> events =
                read.events().subList(0, Math.min(safeSize, read.events().size()));
        return ResponseEntity.ok(new CursorPageDTO<>(
                events.stream().map(e -> toView(e, read.identities())).toList(),
                more
                        ? new TimeIdCursor(
                                        events.getLast().getId().getOccurredAt(),
                                        events.getLast().getId().getId())
                                .encode()
                        : null));
    }

    @GetMapping("/count")
    @Operation(summary = "Count the auth audit events a filter matches", operationId = "adminCountAuthEvents")
    public TotalCountDTO count(@ParameterObject AuditFilterParams filter) {
        return new TotalCountDTO(authAuditService.count(filter.toFilter()));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(
            summary = "Export the filtered audit log as CSV (newest first, capped)",
            operationId = "adminExportAuthEvents")
    @ApiResponse(
            responseCode = "200",
            description = "The newest matching events as CSV",
            headers =
                    @Header(
                            name = EXPORT_LEFT_OUT_HEADER,
                            description = "How many matching events the file leaves out",
                            schema = @Schema(type = "integer", format = "int64")))
    public ResponseEntity<String> export(@ParameterObject AuditFilterParams filter) {
        AuthAuditService.AuditPage data = authAuditService.list(filter.toFilter(), null, EXPORT_MAX_ROWS);
        // Counted after the read: an event that arrives in between is not in the file either.
        long leftOut = Math.max(
                0, authAuditService.count(filter.toFilter()) - data.events().size());
        var identities = data.identities();
        StringBuilder csv = new StringBuilder();
        // A new column is APPENDED, never inserted: an operator's parser keyed on column order
        // keeps
        // working, and one keyed on the header picks the new column up.
        csv.append(
                "occurred_at_utc,event_type,result,account_id,account_name,account_email,acting_account_id,actor_name,actor_email,failure_reason,workspace_id,ip_address,user_agent,details,elevated_via_instance_admin,viewed_actor_id,viewed_account_id\n");
        for (AuthEvent e : data.events()) {
            AuthAuditService.AccountRef account = AuthAuditService.refOf(e.getAccountId(), identities);
            AuthAuditService.AccountRef actor = AuthAuditService.refOf(e.getActingAccountId(), identities);
            Csv.appendRow(
                    csv,
                    Arrays.<@Nullable String>asList(
                            e.getId().getOccurredAt().toString(),
                            e.getEventType().name(),
                            e.getResult().name(),
                            str(e.getAccountId()),
                            account == null ? "" : account.displayName(),
                            account == null ? "" : account.email(),
                            str(e.getActingAccountId()),
                            actor == null ? "" : actor.displayName(),
                            actor == null ? "" : actor.email(),
                            e.getFailureReason(),
                            str(e.getWorkspaceId()),
                            e.getIpInet(),
                            e.getUserAgent(),
                            e.getDetails(),
                            Boolean.toString(e.isElevatedViaInstanceAdmin()),
                            str(e.getViewedActorId()),
                            str(e.getViewedAccountId())));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .header(EXPORT_LEFT_OUT_HEADER, Long.toString(leftOut))
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private static String str(@Nullable Long value) {
        return value == null ? "" : value.toString();
    }

    private static AuthEventViewDTO toView(AuthEvent e, Map<Long, AuthAuditService.AccountRef> identities) {
        return new AuthEventViewDTO(
                e.getId().getId(),
                e.getId().getOccurredAt(),
                e.getEventType().name(),
                e.getResult().name(),
                e.isElevatedViaInstanceAdmin(),
                e.getAccountId(),
                e.getActingAccountId(),
                e.getViewedActorId(),
                e.getViewedAccountId(),
                toRef(AuthAuditService.refOf(e.getAccountId(), identities)),
                toRef(AuthAuditService.refOf(e.getActingAccountId(), identities)),
                e.getFailureReason(),
                e.getWorkspaceId(),
                e.getIpInet(),
                e.getUserAgent(),
                e.getDetails());
    }

    private static @Nullable AuthAuditAccountDTO toRef(AuthAuditService.@Nullable AccountRef ref) {
        return ref == null ? null : new AuthAuditAccountDTO(ref.id(), ref.displayName(), ref.email());
    }
}
