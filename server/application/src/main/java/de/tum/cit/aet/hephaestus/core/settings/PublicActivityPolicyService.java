package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("Instance publication policy applies to every workspace")
class PublicActivityPolicyService implements PublicActivityPolicy {
    private final InstanceSettingsRepository settings;
    private final Optional<AuthEventLogger> audit;
    private final boolean configuredDefault;

    PublicActivityPolicyService(
            InstanceSettingsRepository settings,
            Optional<AuthEventLogger> audit,
            @Value("${hephaestus.public-activity.enabled:false}") boolean configuredDefault) {
        this.settings = settings;
        this.audit = audit;
        this.configuredDefault = configuredDefault;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allowed() {
        return settings.findById(InstanceSettings.SINGLETON_ID)
                .map(InstanceSettings::getPublicActivityAllowed)
                .orElse(configuredDefault);
    }

    @Transactional
    public boolean update(boolean allowed) {
        settings.insertFailSafeSingletonIfMissing();
        var row = settings.findById(InstanceSettings.SINGLETON_ID).orElseThrow();
        row.setPublicActivityAllowed(allowed);
        settings.saveAndFlush(row);
        audit.ifPresent(logger -> logger.event(AuthEvent.EventType.PUBLIC_ACTIVITY_CHANGED, AuthEvent.Result.SUCCESS)
                .actingAccount(CurrentAccount.requireId())
                .details("{\"allowed\":" + allowed + "}")
                .record());
        return allowed;
    }
}
