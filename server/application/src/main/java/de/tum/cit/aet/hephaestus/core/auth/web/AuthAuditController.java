package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthAuditService;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only instance-admin viewer over the append-only {@code auth_event} log (see {@link AuthEvent}).
 * Guarded by the namespaced {@code app_admin} authority. Surfaces the {@code (account_id,
 * acting_account_id)} pair so impersonated actions stay attributable to their operator.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/admin/audit")
@Tag(name = "Admin", description = "Instance-admin account management")
@PreAuthorize("hasAuthority('app_admin')")
public class AuthAuditController {

    /** Hard cap on page size so a malicious/typo'd {@code size} can't scan a whole partition. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Upper bound on a single CSV export so the admin can't pull an unbounded slice of the log. */
    private static final int EXPORT_MAX_ROWS = 10_000;

    private final AuthAuditService authAuditService;

    public AuthAuditController(AuthAuditService authAuditService) {
        this.authAuditService = authAuditService;
    }

    /** A human-readable account identity. {@code displayName}/{@code email} are null for deleted accounts. */
    public record AccountRefDTO(
            @NonNull Long id,
            @Nullable String displayName,
            @Nullable String email) {}

    /**
     * Optional query-param filters for the list/export endpoints, bound as a flattened
     * {@link ParameterObject} so the handler stays under the parameter-count budget. All fields are
     * optional; an absent field means "no constraint on that dimension".
     */
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
            // @NonNull so springdoc marks it required and the client types it `boolean`: the column is
            // NOT NULL, and an optional flag would make every reader write `e.elevated… && …`.
            @NonNull boolean elevatedViaInstanceAdmin,
            @Nullable Long accountId,
            @Nullable Long actingAccountId,
            // Resolved identities for accountId / actingAccountId (null when the account no longer exists);
            // the raw ids stay for back-compat and so deleted-account rows are still attributable by id.
            @Nullable AccountRefDTO account,
            @Nullable AccountRefDTO actor,
            @Nullable String failureReason,
            @Nullable Long workspaceId,
            @Nullable String ipAddress,
            @Nullable String userAgent,
            @Nullable String details) {}

    @GetMapping
    @Operation(summary = "List auth audit events (paged, newest first)", operationId = "adminListAuthEvents")
    public ResponseEntity<Page<AuthEventViewDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @ParameterObject AuditFilterParams filter) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // The query carries its own ORDER BY occurred_at DESC; keep the Pageable sort empty.
        Pageable pageable = PageRequest.of(safePage, safeSize);
        AuthAuditService.AuditPage result0 = authAuditService.list(filter.toFilter(), pageable);
        Page<AuthEventViewDTO> events = result0.events().map(e -> toView(e, result0.identities()));
        return ResponseEntity.ok(events);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(
            summary = "Export the filtered audit log as CSV (newest first, capped)",
            operationId = "adminExportAuthEvents")
    public ResponseEntity<String> export(@ParameterObject AuditFilterParams filter) {
        AuthAuditService.AuditPage data = authAuditService.list(filter.toFilter(), PageRequest.of(0, EXPORT_MAX_ROWS));
        var identities = data.identities();
        StringBuilder csv = new StringBuilder();
        // A new column is APPENDED, never inserted: an operator's parser keyed on column order keeps
        // working, and one keyed on the header picks the new column up.
        csv.append("occurred_at_utc,event_type,result,account_id,account_name,account_email,"
                + "acting_account_id,actor_name,actor_email,failure_reason,workspace_id,ip_address,user_agent,details,"
                + "elevated_via_instance_admin\n");
        for (AuthEvent e : data.events().getContent()) {
            AuthAuditService.AccountRef account = AuthAuditService.refOf(e.getAccountId(), identities);
            AuthAuditService.AccountRef actor = AuthAuditService.refOf(e.getActingAccountId(), identities);
            appendCsvRow(
                    csv,
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
                    Boolean.toString(e.isElevatedViaInstanceAdmin()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private static String str(@Nullable Long value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Append one RFC-4180 CSV row: quote every field, escape embedded quotes, normalize newlines, and
     * neutralize spreadsheet formula injection. Audit cells carry user-controlled text (display names,
     * emails, user-agents, raw details) and the consumer is a privileged admin double-clicking the
     * export — so a value starting with {@code = + - @ TAB CR} would execute as a formula in
     * Excel/Sheets/LibreOffice. Prefix those with a single quote so they render as inert text.
     * See <a href="https://owasp.org/www-community/attacks/CSV_Injection">OWASP CSV Injection</a>.
     */
    private static void appendCsvRow(StringBuilder out, @Nullable String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            String value = fields[i] == null ? "" : fields[i];
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
                value = "'" + value;
            }
            out.append('"')
                    .append(value.replace("\"", "\"\"").replace("\r\n", " ").replace('\n', ' '))
                    .append('"');
        }
        out.append('\n');
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
                toRef(AuthAuditService.refOf(e.getAccountId(), identities)),
                toRef(AuthAuditService.refOf(e.getActingAccountId(), identities)),
                e.getFailureReason(),
                e.getWorkspaceId(),
                e.getIpInet(),
                e.getUserAgent(),
                e.getDetails());
    }

    private static @Nullable AccountRefDTO toRef(AuthAuditService.@Nullable AccountRef ref) {
        return ref == null ? null : new AccountRefDTO(ref.id(), ref.displayName(), ref.email());
    }
}
