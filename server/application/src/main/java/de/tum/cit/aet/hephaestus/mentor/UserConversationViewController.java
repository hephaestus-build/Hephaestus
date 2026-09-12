package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/user-view/users/{userId}/conversations")
@PreAuthorize("hasAuthority('app_admin')")
@Tag(name = "User view")
@RequiredArgsConstructor
@Validated
public class UserConversationViewController {
    private final ChatThreadService threads;

    @GetMapping
    @UserViewRead
    @Operation(summary = "View a user's existing private conversations", operationId = "listUserViewConversations")
    public ResponseEntity<PageResponseDTO<ChatThreadSummaryDTO>> list(
            WorkspaceContext workspace,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PageResponseDTO.from(
                        threads.listSummariesForUser(workspace.id(), userId, PageRequest.of(page, size))));
    }

    @GetMapping("/{threadId}")
    @UserViewRead
    @Operation(
            summary = "Read a user's private conversation without changing it",
            operationId = "getUserViewConversation")
    public ResponseEntity<ChatThreadDetailDTO> get(
            WorkspaceContext workspace, @PathVariable Long userId, @PathVariable UUID threadId) {
        var detail = threads.loadUserThreadDetail(workspace.id(), userId, threadId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ChatThreadDetailDTO(detail.id(), detail.title(), detail.createdAt(), detail.messages()));
    }
}
