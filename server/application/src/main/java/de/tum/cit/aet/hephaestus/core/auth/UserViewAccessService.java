package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.core.auth.stepup.RecentSignInPolicy;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyAssurancePolicy;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("An instance administrator views across workspaces; every event records the one it read")
public class UserViewAccessService implements UserViewAccess {
    private final AuthEventLogger audit;
    private final ObjectMapper mapper;
    private final RecentSignInPolicy recentSignIn;
    private final PasskeyAssurancePolicy passkeys;

    @Override
    public void requireRecentSignIn(@Nullable Authentication authentication, long actingAccountId) {
        passkeys.requireInstanceAdmin(authentication, true);
        recentSignIn.require(authentication, AuthEvent.EventType.USER_VIEW, actingAccountId);
    }

    @Override
    public void record(
            long workspaceId, @Nullable Long actorId, @Nullable Long accountId, String reasonHeader, String read) {
        // A header rather than a query parameter keeps the reason out of access logs and browser
        // history.
        final String reason;
        try {
            reason = UriUtils.decode(reasonHeader, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException invalidEncoding) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The user view reason is not valid URL-encoded text.", invalidEncoding);
        }
        if (reason.isEmpty()
                || reason.length() > 500
                || reason.codePoints().anyMatch(UserViewAccessService::isInvisible)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Give a reason of 1 to 500 characters. Do not use control or format" + " characters.");
        }
        boolean recorded = audit.event(AuthEvent.EventType.USER_VIEW, AuthEvent.Result.SUCCESS)
                .account(SecurityUtils.getCurrentAccountId().orElseThrow())
                .actingAccount(SecurityUtils.getCurrentAccountId().orElseThrow())
                .viewedActor(actorId)
                .viewedAccount(accountId)
                .workspace(workspaceId)
                .details(mapper.writeValueAsString(Map.of("reason", reason, "read", read)))
                .record();
        if (!recorded)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The user view audit is not available.");
    }

    // Invisible format characters can change how a stored reason reads.
    private static boolean isInvisible(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT;
    }
}
