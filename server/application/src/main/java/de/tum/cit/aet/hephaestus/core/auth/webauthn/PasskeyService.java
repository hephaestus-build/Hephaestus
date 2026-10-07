package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PasskeyService implements AccountErasureContributor {
    private final PasskeySessionService sessionLifecycle;
    private final PasskeySecurityEvents securityEvents;
    private final AccountRepository accounts;
    private final PasskeyCredentialRepository credentials;
    private final PasskeyChallengeRepository challenges;
    private final PasskeyRecoveryCodeRepository recoveryCodes;
    private final PasskeyChallengeStore challengeStore;
    private final WebAuthnRelyingPartyOperations operations;
    private final PasskeyJson json;
    private final PasskeyAssurancePolicy assurance;
    private final PasskeyProperties properties;
    private final Clock clock;

    public record PasskeyOptionsDTO(
            @NonNull UUID challengeId, @NonNull String optionsJson) {}

    public record PasskeyCredentialDTO(
            @NonNull String id,
            @NonNull String label,
            @Nullable Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    public record PasskeyStatusDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean protectionEnabled,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean recoveryRequired,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean instanceAdminRequired,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean workspaceAdminRequired,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean verified,

            @NonNull List<PasskeyCredentialDTO> credentials) {}

    public record PasskeyRecoveryCodesDTO(@NonNull List<String> codes) {}

    @Transactional(readOnly = true)
    public PasskeyStatusDTO status(Long accountId) {
        Account account = accounts.findById(accountId).orElseThrow();
        return new PasskeyStatusDTO(
                account.isPasskeyProtectionEnabled(),
                account.isPasskeyRecoveryRequired(),
                properties.instanceAdminRequired(),
                properties.workspaceAdminRequired(),
                assurance.isFresh(authentication()),
                credentials.findByAccountId(accountId).stream()
                        .map(c -> {
                            var record = json.readCredential(c.getRecordJson());
                            return new PasskeyCredentialDTO(
                                    c.getId(), record.getLabel(), record.getCreated(), record.getLastUsed());
                        })
                        .toList());
    }

    @Transactional
    public PasskeyOptionsDTO registrationOptions(Long accountId) {
        Account account = enrollmentAccount(accountId);
        return options(
                account, "REGISTER", operations.createPublicKeyCredentialCreationOptions(() -> authentication()));
    }

    @Transactional
    public void register(Long accountId, UUID challengeId, String credentialJson, String label) {
        String options = challengeStore.consume(challengeId, accountId, CurrentAccount.requireJti(), "REGISTER");
        Account account = enrollmentAccount(accountId);
        PublicKeyCredential<AuthenticatorAttestationResponse> credential =
                json.read(credentialJson, new TypeReference<>() {});
        operations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                json.readCreation(options), new RelyingPartyPublicKey(credential, label)));
        account.setPasskeyProtectionEnabled(true);
        accounts.save(account);
        sessionLifecycle.revokeOthers(accountId);
        securityEvents.changed(account, "registered");
    }

    @Transactional
    public PasskeyOptionsDTO verificationOptions(Long accountId) {
        Account account = sessionLifecycle.lock(accountId);
        if (credentials.findByAccountId(accountId).isEmpty()) {
            throw new PasskeyRequiredException();
        }
        return options(account, "VERIFY", operations.createCredentialRequestOptions(() -> authentication()));
    }

    @Transactional
    public void verify(
            Long accountId,
            UUID challengeId,
            String credentialJson,
            HttpServletRequest request,
            HttpServletResponse response) {
        String options = challengeStore.consume(challengeId, accountId, CurrentAccount.requireJti(), "VERIFY");
        Account account = sessionLifecycle.lock(accountId);
        PublicKeyCredential<AuthenticatorAssertionResponse> credential =
                json.read(credentialJson, new TypeReference<>() {});
        PasskeyCredential row = credentials
                .findById(credential.getRawId().toBase64UrlString())
                .orElseThrow(PasskeyRequiredException::new);
        if (!Objects.equals(row.getAccount().getId(), accountId)) {
            throw new PasskeyRequiredException();
        }
        var user =
                operations.authenticate(new RelyingPartyAuthenticationRequest(json.readRequest(options), credential));
        if (!user.getName().equals(accountId.toString())) {
            throw new PasskeyRequiredException();
        }
        account.setPasskeyRecoveryRequired(false);
        accounts.save(account);
        sessionLifecycle.rotate(accountId, clock.instant(), request, response, false);
        securityEvents.verified(accountId);
    }

    @Transactional
    public void protection(Long accountId, boolean enabled, HttpServletRequest request, HttpServletResponse response) {
        Account account = sessionLifecycle.lock(accountId);
        assurance.requireFresh();
        account.setPasskeyProtectionEnabled(enabled);
        accounts.save(account);
        sessionLifecycle.rotate(accountId, CurrentAccount.passkeyTime(), request, response, true);
        securityEvents.changed(account, enabled ? "protection_enabled" : "protection_disabled");
    }

    @Transactional
    public void remove(Long accountId, String credentialId, HttpServletRequest request, HttpServletResponse response) {
        Account account = sessionLifecycle.lock(accountId);
        assurance.requireFresh();
        PasskeyCredential row = credentials.findById(credentialId).orElseThrow(PasskeyRequiredException::new);
        if (!Objects.equals(row.getAccount().getId(), accountId)) {
            throw new PasskeyRequiredException();
        }
        if (credentials.findByAccountId(accountId).size() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Add another passkey before you remove the last one.");
        }
        credentials.delete(row);
        sessionLifecycle.rotate(accountId, null, request, response, true);
        securityEvents.changed(account, "removed");
    }

    @Transactional
    public PasskeyRecoveryCodesDTO createRecoveryCodes(Long accountId) {
        Account account = sessionLifecycle.lock(accountId);
        assurance.requireFresh();
        recoveryCodes.deleteByAccountId(accountId);
        List<String> codes = IntStream.range(0, 10)
                .mapToObj(i -> Bytes.random().toBase64UrlString())
                .toList();
        recoveryCodes.saveAll(codes.stream()
                .map(code -> new PasskeyRecoveryCode(hash(code), account))
                .toList());
        securityEvents.changed(account, "recovery_codes_replaced");
        return new PasskeyRecoveryCodesDTO(codes);
    }

    @Transactional
    public void recover(Long accountId, String code, HttpServletRequest request, HttpServletResponse response) {
        Account account = sessionLifecycle.lock(accountId);
        if (recoveryCodes.consume(hash(code), accountId) != 1) {
            securityEvents.recoveryRejected(accountId);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The recovery code is not valid. Try another code.");
        }
        account.setPasskeyRecoveryRequired(true);
        accounts.save(account);
        credentials.deleteByAccountId(accountId);
        recoveryCodes.deleteByAccountId(accountId);
        challenges.deleteByAccountId(accountId);
        sessionLifecycle.rotate(accountId, null, request, response, true);
        securityEvents.changed(account, "recovery_started");
    }

    private PasskeyOptionsDTO options(Account account, String purpose, Object options) {
        challenges.deleteByExpiresAtBefore(clock.instant());
        PasskeyChallenge challenge = challenges.save(new PasskeyChallenge(
                account,
                CurrentAccount.requireJti(),
                purpose,
                json.write(options),
                clock.instant().plus(Duration.ofMinutes(5))));
        return new PasskeyOptionsDTO(challenge.getId(), challenge.getOptionsJson());
    }

    private Account enrollmentAccount(Long accountId) {
        Account account = sessionLifecycle.lock(accountId);
        List<PasskeyCredential> registered = credentials.findByAccountId(accountId);
        if (registered.size() >= 20) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Remove a passkey before you add another. The limit is 20.");
        }
        assurance.requireEnrollment(!registered.isEmpty(), account.isPasskeyRecoveryRequired());
        if (account.getPasskeyUserHandle() == null) {
            account.setPasskeyUserHandle(Bytes.random().toBase64UrlString());
            accounts.save(account);
        }
        return account;
    }

    private Authentication authentication() {
        return Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication());
    }

    static String hash(String code) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        challenges.deleteByAccountId(accountId);
        recoveryCodes.deleteByAccountId(accountId);
        credentials.deleteByAccountId(accountId);
        accounts.findById(accountId).ifPresent(a -> {
            a.setPasskeyUserHandle(null);
            a.setPasskeyProtectionEnabled(false);
            a.setPasskeyRecoveryRequired(false);
        });
    }
}
