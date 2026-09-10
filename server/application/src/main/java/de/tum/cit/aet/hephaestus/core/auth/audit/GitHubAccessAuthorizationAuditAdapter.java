package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitHubAccessAuthorizationAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessAuthorizationAuditAdapter implements GitHubAccessAuthorizationAudit {
    private final AuthEventLogger events;

    @Override
    public void authorized(long accountId, long workspaceId, long identityLinkId) {
        events.event(AuthEvent.EventType.GITHUB_ACCESS_AUTHORIZED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .workspace(workspaceId)
                .identityLink(identityLinkId)
                .record();
    }
}
