package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionBusyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;

/**
 * Administrative REST surface for managing per-workspace
 * {@link de.tum.cit.aet.hephaestus.integration.core.connection.Connection} rows — list, read,
 * initiate, status transitions (suspend / reactivate / disconnect via {@code PATCH /status}), audit.
 *
 * <p>Thin HTTP adapter: all repository access lives in {@link ConnectionAdminService};
 * the state machine + audit invariants live in {@link ConnectionService}; per-kind
 * vendor flows live in {@link ConnectionStrategy} implementations. The controller's
 * only jobs are HTTP wire mapping (DTO ↔ entity), strategy resolution, and exception
 * translation.
 *
 * <p>{@link WorkspaceScopedController} prefixes every route with
 * {@code /workspaces/{workspaceSlug}} (the repo-wide convention) so the
 * {@code WorkspaceContextFilter} resolves the tenant + the caller's roles before
 * {@link RequireAtLeastWorkspaceAdmin} runs. The resolved {@link WorkspaceContext} supplies
 * the numeric workspace id to the service layer.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/connections")
@RequireAtLeastWorkspaceAdmin
@Tag(name = "Connections", description = "Workspace integration connection management")
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);

    private static final int AUDIT_PAGE_CAP = 200;

    private final ConnectionAdminService admin;
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final Map<IntegrationKind, ConnectionStrategy> strategies;

    public ConnectionController(
            ConnectionAdminService admin,
            ConnectionService connectionService,
            ObjectMapper objectMapper,
            List<ConnectionStrategy> strategyBeans) {
        this.admin = admin;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toUnmodifiableMap(ConnectionStrategy::kind, s -> s, (a, b) -> {
                    throw new IllegalStateException("Duplicate ConnectionStrategy for kind=" + a.kind()
                            + ": "
                            + a.getClass()
                            + " vs "
                            + b.getClass());
                }));
    }

    @GetMapping
    public ResponseEntity<List<ConnectionSummaryDTO>> list(WorkspaceContext workspace) {
        List<ConnectionSummaryDTO> summaries = admin.listForWorkspace(workspace.id()).stream()
                .map(c -> ConnectionSummaryDTO.from(c, admin.manifests()))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConnectionDetailDTO> read(WorkspaceContext workspace, @PathVariable Long id) {
        Connection connection = admin.findInWorkspaceOrThrow(workspace.id(), id);
        return ResponseEntity.ok(ConnectionDetailDTO.from(connection, admin.manifests(), objectMapper));
    }

    @PostMapping
    @Audited(ledger = AuditLedger.CONNECTION_AUDIT)
    public ResponseEntity<InitiateConnectionResponseDTO> initiate(
            WorkspaceContext workspace,
            @Valid @RequestBody @NotNull InitiateConnectionRequestDTO body,
            @Nullable Authentication authentication) {
        Long workspaceId = workspace.id();
        if (body == null || body.kind() == null) {
            throw new IllegalArgumentException("kind is required");
        }

        ConnectionStrategy strategy = strategies.get(body.kind());
        if (strategy == null) {
            throw new IllegalArgumentException("No ConnectionStrategy registered for kind=" + body.kind());
        }

        // Strategy-level validation failures (e.g. missing 'pat' for GitLab) surface as
        // IllegalArgumentException → 400 ProblemDetail via GlobalControllerAdvice.
        Map<String, String> userInput = body.userInput() == null ? Map.of() : body.userInput();
        ConnectInitiation initiation = strategy.initiate(
                new ConnectionStrategy.InitiateRequest(workspaceId, body.kind(), userInput, actorRef(authentication)));

        return switch (initiation) {
            case ConnectInitiation.RedirectToVendor r ->
                ResponseEntity.ok(InitiateConnectionResponseDTO.redirect(r.vendorUrl()));
            case ConnectInitiation.AcceptInline inline -> {
                Connection connection = admin.createInlineConnection(
                        workspaceId,
                        body.kind(),
                        inline.instanceKey(),
                        inline.credentials(),
                        userInput,
                        actorRef(authentication));
                yield ResponseEntity.ok(InitiateConnectionResponseDTO.linked(connection.getId()));
            }
        };
    }

    /**
     * Resource-oriented lifecycle transition: {@code ACTIVE} (reactivate), {@code SUSPENDED}
     * (suspend), or {@code UNINSTALLED} (disconnect — also best-effort revokes the vendor token).
     * {@code PENDING} is internal to the OAuth handshake and rejected as a bad request; illegal
     * transitions surface as 400 via the state machine.
     */
    @PatchMapping("/{id}/status")
    @Operation(operationId = "updateConnectionStatus")
    @Audited(ledger = AuditLedger.CONNECTION_AUDIT)
    public ResponseEntity<ConnectionSummaryDTO> updateStatus(
            WorkspaceContext workspace,
            @PathVariable Long id,
            @RequestBody @Valid @NotNull UpdateConnectionStatusRequestDTO body,
            @Nullable Authentication authentication) {
        Connection connection = admin.findInWorkspaceOrThrow(workspace.id(), id);
        if (connection.getKind() == IntegrationKind.KEYCLOAK_DIRECTORY) {
            throw new IllegalArgumentException(
                    "Manage directory availability and teardown through the workspace directory policy");
        }
        IntegrationState target = body.state();
        String eventType =
                switch (target) {
                    case ACTIVE -> "REACTIVATE";
                    case SUSPENDED -> "SUSPEND";
                    case UNINSTALLED -> "DISCONNECT";
                    case PENDING ->
                        throw new IllegalArgumentException("PENDING is not an admin-settable connection state");
                };

        String correlationId = eventType.toLowerCase(Locale.ROOT) + "-" + connection.getId() + "-" + UUID.randomUUID();
        TransitionRequest request = new TransitionRequest(
                target, eventType, "ADMIN", actorRef(authentication), correlationId, body.reason());
        Connection updated = target == IntegrationState.UNINSTALLED
                ? connectionService.disconnect(connection, request, () -> revokeBestEffort(connection))
                : connectionService.transition(connection, request);
        return ResponseEntity.ok(ConnectionSummaryDTO.from(updated, admin.manifests()));
    }

    /**
     * Vendor-side revoke callback handed to {@link ConnectionService#disconnect}, which invokes it
     * before the UNINSTALLED transition. A missing strategy (the kind was de-registered after the row
     * was written) is a local no-op — there is nothing to call.
     *
     * <p>A failing {@code revoke} is deliberately NOT caught here. The revoke reaches
     * {@code @Transactional} erasers, so a swallowed {@code DataAccessException} would leave the
     * surrounding transaction rollback-only and the "proceed locally" promise would be a lie that
     * surfaces as a 500 at commit. {@code ConnectionService} instead runs this callback in its own
     * transaction and absorbs the failure there, which is the only place the absorption actually
     * works. Letting it propagate from here is what hands it the failure to absorb.
     */
    private void revokeBestEffort(Connection connection) {
        ConnectionStrategy strategy = strategies.get(connection.getKind());
        if (strategy == null) {
            log.warn(
                    "No ConnectionStrategy registered for kind={} on disconnect of connection={} — local transition only",
                    connection.getKind(),
                    connection.getId());
            return;
        }
        strategy.revoke(connection.toRef());
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<List<ConnectionAuditEntryDTO>> audit(WorkspaceContext workspace, @PathVariable Long id) {
        // findInWorkspaceOrThrow enforces the workspace scope before we expose audit history,
        // so cross-workspace audit reads return 404 rather than leaking a partial trail.
        admin.findInWorkspaceOrThrow(workspace.id(), id);
        List<ConnectionAuditEntryDTO> entries = admin.auditForConnection(id, AUDIT_PAGE_CAP).stream()
                .map(ConnectionAuditEntryDTO::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    private static String actorRef(@Nullable Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return "anonymous";
        return authentication.getName();
    }

    /**
     * Not-found is signalled as {@link NoSuchElementException} by {@code ConnectionAdminService}
     * (deliberately undistinguished from cross-workspace reads to avoid leaking workspace
     * boundaries). It is mapped locally — rather than globally — so a stray {@code Optional.get()}
     * elsewhere in the app still surfaces as a 500, not a misleading 404.
     * {@code EntityNotFoundException}, {@code IllegalStateException}, {@code IllegalArgumentException}
     * and {@code DataIntegrityViolationException} are all handled by {@code GlobalControllerAdvice}.
     */
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail handleNotFound(NoSuchElementException e) {
        log.info("Connection lookup 404: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Resource not found");
        return problem;
    }

    @ExceptionHandler(ConnectionBusyException.class)
    ProblemDetail handleBusyConnection(ConnectionBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Connection has an active sync job");
        problem.setProperty("connectionId", e.connectionId());
        problem.setProperty("jobId", e.jobId());
        return problem;
    }
}
