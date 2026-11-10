package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
public class PasskeyAssurancePolicy implements AdminPasskeyAccess {
    private final PasskeySecurityEvents events;
    private final PasskeyProperties properties;
    private final AccountRepository accounts;
    private final AuthProperties auth;
    private final Clock clock;

    public PasskeyAssurancePolicy(
            PasskeyProperties properties,
            AccountRepository accounts,
            AuthProperties auth,
            Clock clock,
            PasskeySecurityEvents events) {
        this.events = events;
        this.properties = properties;
        this.accounts = accounts;
        this.auth = auth;
        this.clock = clock;
    }

    @Override
    public void requireWorkspaceAdmin(boolean workspaceRequired, boolean elevated, boolean sensitive) {
        require(SecurityContextHolder.getContext().getAuthentication(), elevated, workspaceRequired, sensitive, true);
    }

    public void requireInstanceAdmin(@Nullable Authentication authentication, boolean sensitive) {
        require(authentication, true, false, sensitive, false);
    }

    public void requirePersonal(@Nullable Authentication authentication) {
        require(authentication, false, false, true, false);
    }

    private void require(
            @Nullable Authentication authentication,
            boolean instanceAdmin,
            boolean workspaceRequired,
            boolean sensitive,
            boolean workspace) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new PasskeyRequiredException();
        }
        Long accountId;
        try {
            accountId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The token subject is not an account ID.", e);
        }
        Account account = accounts.findById(accountId).orElseThrow(PasskeyRequiredException::new);
        if (!auth.assuranceEnforced()) {
            return;
        }
        boolean needsProof = account.isPasskeyProtectionEnabled()
                || account.isPasskeyRecoveryRequired()
                || ((instanceAdmin || account.getAppRole() == Account.AppRole.APP_ADMIN)
                        && properties.instanceAdminRequired())
                || (workspace && (workspaceRequired || properties.workspaceAdminRequired()));
        if (!needsProof) {
            return;
        }
        if (account.isPasskeyRecoveryRequired()
                || !verified(authentication, sensitive ? auth.stepUpMaxAge() : properties.adminMaxAge())) {
            events.required(Objects.requireNonNull(account.getId()));
            throw new PasskeyRequiredException();
        }
    }

    @Override
    public boolean assuranceEnforced() {
        return auth.assuranceEnforced();
    }

    @Override
    public boolean workspaceAdminRequired() {
        return properties.workspaceAdminRequired();
    }

    @Override
    public void requireFresh() {
        // A user view acts for another account, so its operator's proof never authorizes the action.
        if (UserViewContextHolder.get() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This action is not available in a user view.");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!auth.assuranceEnforced()) {
            if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt) || jwt.hasClaim("sid")) {
                throw new PasskeyRequiredException();
            }
            requirePersonal(authentication);
            return;
        }
        if (!verified(authentication, auth.stepUpMaxAge())) {
            Long accountId = CurrentAccount.idOrNull();
            if (accountId != null) {
                events.required(accountId);
            }
            throw new PasskeyRequiredException();
        }
    }

    public boolean isFresh(@Nullable Authentication authentication) {
        return verified(authentication, auth.stepUpMaxAge());
    }

    public void requireEnrollment(boolean enrolled, boolean recovering) {
        if (!auth.assuranceEnforced()) {
            requireFresh();
            return;
        }
        if (enrolled) {
            requireFresh();
            return;
        }
        if (recovering) {
            return;
        }
        AllRequiredFactorsAuthorizationManager<Object> manager =
                AllRequiredFactorsAuthorizationManager.<Object>builder()
                        .requireFactor(f -> f.authorizationCodeAuthority().validDuration(auth.stepUpMaxAge()))
                        .build();
        manager.setClock(clock);
        var result = manager.authorize(() -> SecurityContextHolder.getContext().getAuthentication(), this);
        if (result == null || !result.isGranted()) {
            throw new StepUpRequiredException(auth.stepUpMaxAge());
        }
    }

    public boolean verified(@Nullable Authentication authentication, Duration duration) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt) || jwt.hasClaim("sid")) {
            return false;
        }
        Object time = jwt.getClaim("passkey_time");
        if (!(time instanceof Number seconds)) {
            return false;
        }
        Instant instant = Instant.ofEpochSecond(seconds.longValue());
        if (instant.isAfter(clock.instant().plusSeconds(30))) {
            return false;
        }
        AllRequiredFactorsAuthorizationManager<Object> manager =
                AllRequiredFactorsAuthorizationManager.<Object>builder()
                        .requireFactor(f -> f.webauthnAuthority().validDuration(duration))
                        .build();
        manager.setClock(clock);
        var result = manager.authorize(() -> authentication, this);
        return result != null && result.isGranted();
    }
}
