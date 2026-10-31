package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.auth.spi.InstallationReplayAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class InstallationReplayAuditAdapter implements InstallationReplayAudit {
    private final AuthEventLogger audit;

    public InstallationReplayAuditAdapter(AuthEventLogger audit) {
        this.audit = audit;
    }

    @Override
    public boolean record(long actingAccountId, long deadLetterId) {
        return audit.event(AuthEvent.EventType.INSTALLATION_EVENT_REPLAYED, AuthEvent.Result.SUCCESS)
                .account(actingAccountId)
                .actingAccount(actingAccountId)
                .details("{\"deadLetterId\":" + deadLetterId + "}")
                .recordInCurrentTransaction();
    }
}
