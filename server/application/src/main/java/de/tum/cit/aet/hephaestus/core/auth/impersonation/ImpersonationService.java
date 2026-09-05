package de.tum.cit.aet.hephaestus.core.auth.impersonation;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Impersonation as JWT reissuance with the RFC 8693 {@code act} claim.
 *
 * <p>In a stateless cookie-JWT BFF there is no server session for {@code SwitchUserFilter}
 * to mutate — impersonation is "mint a token for the target carrying the operator's id in
 * {@code act}." {@link de.tum.cit.aet.hephaestus.core.security.ImpersonationGuard}
 * enforces read-only-by-default on any request whose JWT carries {@code act} (now actually
 * registered on the resource-server chain — it was previously dead code).
 *
 * <p>Both begin and exit are audited with the {@code (account_id=target,
 * acting_account_id=operator)} pair so every action taken under impersonation is
 * attributable to the operator.
 */
@ConditionalOnServerRole
@Service
public class ImpersonationService {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationService.class);

    private final AccountRepository accountRepository;
    private final HephaestusJwtIssuer jwtIssuer;
    private final JwtPrincipalFactory principalFactory;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AuthEventLogger authEventLogger;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;
    private final Clock clock;

    public ImpersonationService(
            AccountRepository accountRepository,
            HephaestusJwtIssuer jwtIssuer,
            JwtPrincipalFactory principalFactory,
            IssuedJwtRepository issuedJwtRepository,
            AuthEventLogger authEventLogger,
            ObjectMapper objectMapper,
            AuthProperties properties,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.jwtIssuer = jwtIssuer;
        this.principalFactory = principalFactory;
        this.issuedJwtRepository = issuedJwtRepository;
        this.authEventLogger = authEventLogger;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of begin/exit — the freshly-minted token the controller writes to the cookie. */
    public record Result(
            HephaestusJwtIssuer.Token token,
            Long targetAccountId,
            @Nullable Long actingAccountId) {}

    /**
     * Begin impersonating {@code targetAccountId} as {@code operatorAccountId}. The operator
     * MUST be an {@code APP_ADMIN} (the controller enforces this via method security too;
     * we re-check here as defence in depth). A {@code reason} is mandatory and audited.
     *
     * @param operatorAuthTime the operator's {@code auth_time}, stamped onto the impersonation token so
     *                         exiting returns to a session as old as the one that began it.
     */
    @Transactional
    public Result begin(
            Long operatorAccountId,
            Long targetAccountId,
            String reason,
            @Nullable Instant operatorAuthTime,
            @Nullable HttpServletRequest request) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "impersonation reason is required");
        }
        Account operator = accountRepository
                .findById(operatorAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "operator account not found"));
        if (operator.getAppRole() != Account.AppRole.APP_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only app admins may impersonate");
        }
        Account target = accountRepository
                .findById(targetAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "target account not found"));
        if (Objects.requireNonNull(target.getId()).equals(operator.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot impersonate yourself");
        }
        // Privilege-escalation / repudiation guard: an APP_ADMIN must not impersonate another
        // APP_ADMIN. ImpersonationGuard is an accidental-write guardrail, not a hardened authz control
        // (it cannot stop a malicious operator who sends the allow-writes header), so the real defence
        // is constraining WHO may be impersonated. Lateral admin-to-admin impersonation lets one admin
        // act under a peer admin's identity — an attribution-laundering escalation vector.
        if (target.getAppRole() == Account.AppRole.APP_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot impersonate another app admin");
        }

        // Impersonate with the target's own roles so the operator sees the target's view;
        // ImpersonationGuard makes the session read-only regardless. The act claim records
        // the operator. Issued via the principal factory so preferred_username = target login.
        // imp_exp stamps the absolute time-box so silent refresh can't renew it indefinitely.
        Instant impersonationExpiresAt = clock.instant().plus(properties.impersonationMaxLifetime());
        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccount(target),
                TokenConstraints.impersonation(operatorAccountId, impersonationExpiresAt, null, operatorAuthTime),
                request);

        authEventLogger
                .event(AuthEvent.EventType.IMPERSONATION_BEGIN, AuthEvent.Result.SUCCESS)
                .account(target.getId())
                .actingAccount(operator.getId())
                .details(writeReasonJson(reason))
                .record();
        log.info("auth.impersonation: operator={} began impersonating target={}", operator.getId(), target.getId());

        return new Result(token, target.getId(), operator.getId());
    }

    /**
     * Exit impersonation: revoke the current impersonation JWT and mint a fresh operator
     * token. {@code currentJti} is the jti of the impersonation token (from the request's
     * validated JWT).
     */
    @Transactional
    public Result exit(
            Long operatorAccountId,
            Long targetAccountId,
            UUID currentJti,
            @Nullable Instant operatorAuthTime,
            @Nullable HttpServletRequest request) {
        issuedJwtRepository.revoke(currentJti, clock.instant(), IssuedJwt.RevokedReason.IMPERSONATION_EXIT);
        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccountId(operatorAccountId),
                TokenConstraints.session(null, operatorAuthTime),
                request);

        authEventLogger
                .event(AuthEvent.EventType.IMPERSONATION_END, AuthEvent.Result.SUCCESS)
                .account(targetAccountId)
                .actingAccount(operatorAccountId)
                .record();
        log.info(
                "auth.impersonation: operator={} exited impersonation of target={}",
                operatorAccountId,
                targetAccountId);

        return new Result(token, operatorAccountId, null);
    }

    /**
     * Serialize the audit details via Jackson so control characters ({@code \n}, {@code \t}, …)
     * and quotes in the operator-supplied {@code reason} are escaped correctly. The previous
     * hand-rolled {@code escape()} only handled {@code \\} and {@code "}, so a reason containing a
     * newline produced invalid JSON in {@code auth_event.details}.
     */
    private String writeReasonJson(String reason) {
        return objectMapper.writeValueAsString(Map.of("reason", reason));
    }
}
