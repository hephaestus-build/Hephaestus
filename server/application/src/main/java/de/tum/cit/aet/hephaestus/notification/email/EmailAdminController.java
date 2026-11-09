package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
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
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an instance admin prove the relay end to end. The test email goes through the same outbox and
 * {@link EmailAdapter} as every notification — Silent Mode, the sender identity and the headers
 * included — so {@code SENT} proves relay acceptance, not inbox delivery. Every outcome is
 * HTTP 200 with the outcome named in the body; malformed requests remain HTTP 400.
 */
@ConditionalOnServerRole
@RestController
@WorkspaceAgnostic("Instance-wide relay verification — deliberately cross-tenant, app_admin only")
@RequestMapping("/admin/email")
@Tag(name = "Instance Email", description = "Instance-wide email transport verification")
@RecentSignInExempt(reason = "sends one test email; changes no access and stores no credential")
@RequireInstanceAdmin
public class EmailAdminController {

    /** A test email that waits behind the rate limit longer than this is no longer a test of now. */
    static final Duration TEST_EXPIRY = Duration.ofMinutes(2);

    private final Emails emails;
    private final ActionExecutor executor;
    private final TestEmailNotice notice;
    private final AccountContactQuery contacts;
    private final Clock clock;

    EmailAdminController(
            Emails emails, ActionExecutor executor, TestEmailNotice notice, AccountContactQuery contacts, Clock clock) {
        this.emails = emails;
        this.executor = executor;
        this.notice = notice;
        this.contacts = contacts;
        this.clock = clock;
    }

    /** @param configured this instance has a relay and a sender, so it can try to send email at all */
    public record EmailStatusDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean configured) {}

    @GetMapping("/status")
    @Operation(operationId = "getEmailStatus", summary = "Whether this instance can send email at all")
    public EmailStatusDTO status() {
        return new EmailStatusDTO(emails.configured());
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
        long adminId = CurrentAccount.requireId();
        String to = body == null || body.to() == null || body.to().isBlank()
                ? contacts.verifiedPrimaryEmail(adminId).orElse(null)
                : body.to().trim();
        if (to == null) {
            return new EmailTestResponseDTO(Outcome.NO_RECIPIENT, null, null);
        }
        var event = new TestEmailNotice.TestEmail(
                UUID.randomUUID(), to, adminId, clock.instant().plus(TEST_EXPIRY));
        // One attempt on this thread, through the outbox like every email.
        ActionResult result = executor.runNow(emails.request(notice, event, 1)).orElseThrow();
        return new EmailTestResponseDTO(
                EmailAdapter.outcome(result), to, result instanceof ActionResult.Done done ? done.readbackRef() : null);
    }
}
