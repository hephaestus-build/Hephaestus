package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnServerRole
@WorkspaceAgnostic("Opaque token authorizes only disabling one account subscription")
@PreAuthorize("permitAll()")
@RecentSignInExempt(reason = "RFC 8058 unsubscribe must work without an authenticated session")
@Tag(name = "Notification preferences")
public class EmailUnsubscribeController {
    private final NotificationSubscriptionService subscriptions;

    public EmailUnsubscribeController(NotificationSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping(
            value = "/notifications/unsubscribe/{token}",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Subscription disabled, or token no longer applicable")
    @SecurityRequirements
    @Operation(operationId = "unsubscribeEmail", summary = "Unsubscribe from one optional email subscription")
    @AuditExempt(reason = "Anonymous scoped opt-out; recording the bearer token would disclose the capability")
    public ResponseEntity<Void> unsubscribe(
            @PathVariable String token, @RequestParam("List-Unsubscribe") String action) {
        if (!"One-Click".equals(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a one-click unsubscribe request");
        }
        subscriptions.unsubscribe(token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
