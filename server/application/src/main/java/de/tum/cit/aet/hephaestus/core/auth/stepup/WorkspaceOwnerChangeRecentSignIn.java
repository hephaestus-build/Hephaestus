package de.tum.cit.aet.hephaestus.core.auth.stepup;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceOwnerChangeAssurance;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The same recent sign-in policy as an instance-admin action, recorded on {@code WORKSPACE_OWNER_CHANGE}. */
@Component
@ConditionalOnServerRole
class WorkspaceOwnerChangeRecentSignIn implements WorkspaceOwnerChangeAssurance {
    private final RecentSignInPolicy policy;

    WorkspaceOwnerChangeRecentSignIn(RecentSignInPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void requireRecentSignIn() {
        policy.require(
                SecurityContextHolder.getContext().getAuthentication(),
                AuthEvent.EventType.WORKSPACE_OWNER_CHANGE,
                SecurityUtils.getCurrentAccountId().orElse(null));
    }
}
