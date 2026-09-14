package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnServerRole
@RequiredArgsConstructor
@RequestMapping("/user/notification-preferences")
@PreAuthorize("isAuthenticated()")
@RecentSignInExempt(reason = "Changes only the current account's optional email subscriptions")
@WorkspaceAgnostic("Current-account subscriptions are independent of workspace membership")
public class NotificationPreferencesController {
    private final NotificationSubscriptionService subscriptions;

    @GetMapping
    @Operation(operationId = "getNotificationPreferences", summary = "Get your email subscriptions")
    @ApiResponse(
            responseCode = "200",
            description = "Your current email subscriptions",
            headers =
                    @Header(
                            name = "ETag",
                            description = "Strong validator for If-Match",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = NotificationPreferencesDTO.class)))
    public ResponseEntity<NotificationPreferencesDTO> get() {
        var result = subscriptions.get(CurrentAccount.requireId());
        return ResponseEntity.ok().eTag(result.etag()).body(result);
    }

    @PutMapping
    @Operation(operationId = "updateNotificationPreferences", summary = "Update your email subscriptions")
    @ApiResponse(
            responseCode = "200",
            description = "Email subscriptions updated",
            headers =
                    @Header(
                            name = "ETag",
                            description = "Strong validator for the updated preferences",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = NotificationPreferencesDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid preferences or If-Match header",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Enabling this subscription requires instance administration",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Enabling email requires a verified contact on an active account",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<NotificationPreferencesDTO> update(
            @jakarta.validation.Valid @RequestBody UpdateNotificationPreferencesDTO request,
            @Parameter(required = true, description = "Current preferences ETag")
                    @RequestHeader(value = "If-Match", required = false)
                    @Nullable
                    String ifMatch,
            Authentication authentication) {
        if (ifMatch == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        }
        EntityTagPrecondition precondition;
        try {
            precondition = EntityTagPrecondition.parse(ifMatch);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must be a valid entity tag", invalid);
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "app_admin".equals(a.getAuthority()));
        var result = subscriptions.update(CurrentAccount.requireId(), request, precondition, admin);
        return ResponseEntity.ok().eTag(result.etag()).body(result);
    }
}
