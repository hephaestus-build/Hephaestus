package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.auth.spi.McpClientGrants;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.McpClientGrantDTO;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/** The MCP clients that members connected to this workspace; admins list and revoke them (ADR 0110). */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/mcp-grants")
@RequiredArgsConstructor
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
@RequireAtLeastWorkspaceAdmin
public class McpClientGrantController {

    private final McpClientGrants grants;
    private final AccountReferenceService accounts;

    @GetMapping
    @Operation(operationId = "listMcpClientGrants", summary = "List the MCP clients connected to the workspace")
    public List<McpClientGrantDTO> list(WorkspaceContext workspace) {
        List<McpClientGrants.Grant> active = grants.activeGrants(workspace.id());
        Map<Long, AccountRefDTO> approvers = accounts.references(
                workspace.id(),
                active.stream().map(McpClientGrants.Grant::accountId).distinct().toList());
        return active.stream()
                .map(grant -> new McpClientGrantDTO(
                        grant.id(),
                        grant.clientHost(),
                        grant.clientName(),
                        Objects.requireNonNull(approvers.get(grant.accountId())),
                        grant.scopes(),
                        grant.approvedAt(),
                        grant.passkeyBacked(),
                        grant.lastUsedAt(),
                        grant.expiresAt()))
                .toList();
    }

    @DeleteMapping("/{grantId}")
    @RecentSignInExempt(reason = "disconnects an MCP client, which an admin can connect again; grants no access")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "MCP_CLIENT_REVOKED")
    @Operation(operationId = "revokeMcpClientGrant", summary = "Disconnect an MCP client from the workspace")
    public ResponseEntity<Void> revoke(WorkspaceContext workspace, @PathVariable UUID grantId) {
        if (!grants.revoke(workspace.id(), grantId, CurrentAccount.requireId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The workspace has no such connected client.");
        }
        return ResponseEntity.noContent().build();
    }
}
