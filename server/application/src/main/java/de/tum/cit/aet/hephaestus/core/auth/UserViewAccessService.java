package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.LinkedAccountRow;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("An instance administrator views across workspaces; every event records the one it read")
public class UserViewAccessService implements UserViewAccess {
    private final IdentityLinkRepository identityLinks;
    private final AuthEventLogger audit;
    private final ObjectMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, LinkedAccount> linkedAccounts(Collection<Long> userIds) {
        return identityLinks.findLinkedAccountsByExternalActorIds(userIds).stream()
                .collect(Collectors.toMap(
                        LinkedAccountRow::externalActorId,
                        row -> new LinkedAccount(row.accountId(), row.status().name())));
    }

    @Override
    public void record(long workspaceId, long userId, @Nullable Long accountId, String reasonHeader, String read) {
        // A header rather than a query parameter keeps the reason out of access logs and browser history.
        final String reason;
        try {
            reason = UriUtils.decode(reasonHeader, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException invalidEncoding) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid user-view reason encoding", invalidEncoding);
        }
        if (reason.isEmpty()
                || reason.length() > 500
                || reason.codePoints().anyMatch(UserViewAccessService::isInvisible)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provide a reason of 1–500 characters without control or format characters");
        }
        boolean recorded = audit.event(AuthEvent.EventType.USER_VIEW, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(SecurityUtils.getCurrentAccountId().orElseThrow())
                .viewedUser(userId)
                .workspace(workspaceId)
                .details(mapper.writeValueAsString(Map.of("reason", reason, "read", read)))
                .record();
        if (!recorded)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User view audit is unavailable");
    }

    /** Format characters (bidi overrides, zero-width joiners) would make the stored reason read differently. */
    private static boolean isInvisible(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT;
    }
}
