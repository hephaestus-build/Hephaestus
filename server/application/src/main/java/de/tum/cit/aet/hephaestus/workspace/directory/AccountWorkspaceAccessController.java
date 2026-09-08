package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryAccessService.WorkspaceAccessOfferDTO;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A current-account resource, available before the caller belongs to any workspace. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/user/workspace-access")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountWorkspaceAccessController {
    private final DirectoryAccessService access;

    @GetMapping
    @Operation(
            operationId = "getWorkspaceAccessOffers",
            summary = "List workspace offers from your verified organizational identity")
    public List<WorkspaceAccessOfferDTO> offers() {
        return access.offers();
    }

    @PostMapping("/{workspaceId}")
    @Operation(
            operationId = "joinDirectoryWorkspace",
            summary = "Join a workspace using your current directory eligibility")
    public WorkspaceAccessOfferDTO join(@PathVariable Long workspaceId) {
        return access.join(workspaceId);
    }
}
