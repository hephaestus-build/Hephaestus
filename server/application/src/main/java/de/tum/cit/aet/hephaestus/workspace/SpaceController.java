package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.SpaceDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's personal space and their team workspaces. The first call creates the personal space, in its
 * own transaction, before the list is read.
 */
@RestController
@ConditionalOnServerRole
@Validated
@RequestMapping("/user/spaces")
@Tag(name = "Account")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class SpaceController {

    private final PersonalSpaces personalSpaces;

    @GetMapping
    @Operation(
            summary = "List your personal space and your team workspaces",
            description = "The first call creates your personal space. The first page starts with it, followed by"
                    + " the team workspaces that the workspace list shows you, by name: an active workspace where your"
                    + " membership is active, and a suspended one where your membership is active with the admin or"
                    + " owner role. No workspace route opens a personal space yet.",
            operationId = "listSpaces")
    public OffsetPageDTO<SpaceDTO> listSpaces(
            // With at most 100 rows a page, the offset stays within the int that a JPA query takes.
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000_000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        long accountId = CurrentAccount.requireId();
        personalSpaces.ensure(accountId);
        return personalSpaces.spaces(accountId, page, size);
    }
}
