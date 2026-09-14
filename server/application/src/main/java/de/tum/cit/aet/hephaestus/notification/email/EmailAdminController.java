package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an instance admin prove the relay end to end. The test email goes through the same
 * {@link EmailGateway} as every notification — Silent Mode, the sender identity and the headers
 * included — so {@code SENT} proves relay acceptance, not inbox delivery. Every outcome is
 * HTTP 200 with the outcome named in the body; malformed requests remain HTTP 400.
 */
@ConditionalOnServerRole
@RestController
@WorkspaceAgnostic("Instance-wide relay verification — deliberately cross-tenant, app_admin only")
@RequestMapping("/admin/email")
@Tag(name = "Instance Email", description = "Instance-wide email transport verification")
@RecentSignInExempt(reason = "sends one test email; changes no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
public class EmailAdminController {

    private final EmailGateway gateway;
    private final EmailRenderer renderer;
    private final AccountContactQuery contacts;

    public EmailAdminController(EmailGateway gateway, EmailRenderer renderer, AccountContactQuery contacts) {
        this.gateway = gateway;
        this.renderer = renderer;
        this.contacts = contacts;
    }

    @PostMapping("/test")
    @Operation(
            operationId = "adminSendTestEmail",
            summary = "Send a test email",
            description = "Sends a test email through the configured relay to the given address, "
                    + "or to the caller's verified address when none is given.")
    @ApiResponse(responseCode = "200", description = "Email delivery outcome")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request or invalid recipient address",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @AuditExempt(reason = "sends one email to verify the relay; stores no configuration")
    public EmailTestResponseDTO sendTestEmail(
            @Valid @RequestBody(required = false) @Nullable EmailTestRequestDTO body) {
        String to = body == null || body.to() == null || body.to().isBlank()
                ? contacts.verifiedPrimaryEmail(CurrentAccount.requireId()).orElse(null)
                : body.to().trim();
        if (to == null) {
            return new EmailTestResponseDTO(Outcome.NO_RECIPIENT, null, null);
        }
        RenderedEmail rendered = renderer.render(EmailKind.TEST_MESSAGE, Map.of());
        EmailDeliveryResult result = gateway.send(EmailMessage.of(EmailKind.TEST_MESSAGE, to, rendered));
        return new EmailTestResponseDTO(result.outcome(), to, result.messageId());
    }
}
