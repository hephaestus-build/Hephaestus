package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequestMapping("/user/passkeys")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Passkeys")
@RequiredArgsConstructor
public class PasskeyController {
    private final PasskeyService service;

    public record RegisterPasskeyRequestDTO(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 65536) String credentialJson,
            @NotBlank @Size(max = 100) String label) {}

    public record VerifyPasskeyRequestDTO(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 65536) String credentialJson) {}

    public record PasskeyProtectionRequestDTO(@NotNull Boolean enabled) {}

    public record RecoverPasskeyRequestDTO(
            @NotBlank @Size(max = 256) String code) {}

    @GetMapping
    @Operation(operationId = "getPasskeyStatus", summary = "Get passkey settings")
    public PasskeyService.PasskeyStatusDTO status() {
        return service.status(CurrentAccount.requireId());
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping("/registration-options")
    @Operation(operationId = "getPasskeyRegistrationOptions", summary = "Start passkey enrollment")
    public PasskeyService.PasskeyOptionsDTO registrationOptions() {
        return service.registrationOptions(CurrentAccount.requireId());
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping
    @Operation(operationId = "registerPasskey", summary = "Register a passkey")
    public void register(@Valid @RequestBody RegisterPasskeyRequestDTO body) {
        service.register(CurrentAccount.requireId(), body.challengeId(), body.credentialJson(), body.label());
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping("/verification-options")
    @Operation(operationId = "getPasskeyVerificationOptions", summary = "Start passkey verification")
    public PasskeyService.PasskeyOptionsDTO verificationOptions() {
        return service.verificationOptions(CurrentAccount.requireId());
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping("/verification")
    @Operation(operationId = "verifyPasskey", summary = "Verify a passkey for this browser session")
    public void verify(
            @Valid @RequestBody VerifyPasskeyRequestDTO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.verify(CurrentAccount.requireId(), body.challengeId(), body.credentialJson(), request, response);
    }

    @AuditExempt(reason = "PasskeyService records protection changes")
    @PatchMapping("/protection")
    @Operation(operationId = "updatePasskeyProtection", summary = "Update personal admin protection")
    public void protection(
            @Valid @RequestBody PasskeyProtectionRequestDTO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.protection(CurrentAccount.requireId(), body.enabled(), request, response);
    }

    @AuditExempt(reason = "PasskeyService records credential removal")
    @DeleteMapping("/{credentialId}")
    @Operation(operationId = "removePasskey", summary = "Remove a passkey")
    public void remove(@PathVariable String credentialId, HttpServletRequest request, HttpServletResponse response) {
        service.remove(CurrentAccount.requireId(), credentialId, request, response);
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping("/recovery-codes")
    @Operation(operationId = "createPasskeyRecoveryCodes", summary = "Replace passkey recovery codes")
    public PasskeyService.PasskeyRecoveryCodesDTO codes() {
        return service.createRecoveryCodes(CurrentAccount.requireId());
    }

    @AuditExempt(reason = "PasskeyService records credential changes and verification")
    @PostMapping("/recovery")
    @Operation(operationId = "recoverPasskeys", summary = "Start restricted passkey recovery")
    public void recover(
            @Valid @RequestBody RecoverPasskeyRequestDTO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.recover(CurrentAccount.requireId(), body.code(), request, response);
    }
}
