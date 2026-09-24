package de.tum.cit.aet.hephaestus.account.userview;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/user-view/users")
@PreAuthorize("hasAuthority('app_admin')")
@Tag(name = "User view", description = "Instance-admin-only read-only access to users")
@RequiredArgsConstructor
@Validated
public class UserViewUsersController {
    private final ViewedUserService users;

    public record UserViewUserDTO(
            @NonNull Long userId,
            @NonNull String login,
            @Nullable String name,
            @Nullable Long accountId,
            @Nullable String accountStatus) {}

    @GetMapping
    @Operation(summary = "List workspace users and their linked account status", operationId = "listUserViewUsers")
    public ResponseEntity<PageResponseDTO<UserViewUserDTO>> list(
            WorkspaceContext workspace,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PageResponseDTO.from(users.list(workspace.id(), PageRequest.of(page, size))));
    }
}
