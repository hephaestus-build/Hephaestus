package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationTargetEventDTO;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub installation_target webhook events.
 */
@Component
@ConditionalOnServerRole
public class GitHubInstallationTargetMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubInstallationTargetEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationTargetMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final ProvisioningListener provisioningListener;
    private final OrganizationService organizationService;
    private final IdentityProviderRepository gitProviderRepository;

    GitHubInstallationTargetMessageHandler(
            ProvisioningListener provisioningListener,
            OrganizationService organizationService,
            IdentityProviderRepository gitProviderRepository,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "installation." + GitHubEventType.INSTALLATION_TARGET.getValue(),
                GitHubInstallationTargetEventDTO.class,
                deserializer,
                transactionTemplate);
        this.provisioningListener = provisioningListener;
        this.organizationService = organizationService;
        this.gitProviderRepository = gitProviderRepository;
    }

    @Override
    protected void handleEvent(GitHubInstallationTargetEventDTO event) {
        if (event.actionType() != GitHubEventAction.InstallationTarget.RENAMED) {
            return;
        }

        var installation = event.installation();
        var account = event.account();

        if (installation == null || account == null) {
            log.warn("Received installation_target event with missing data: action={}", event.action());
            return;
        }

        long installationId = installation.id();
        String newLogin = account.login();

        if (newLogin == null || newLogin.isBlank()) {
            log.warn("Skipped installation_target event: reason=emptyLogin, installationId={}", installationId);
            return;
        }

        String previousLogin = null;
        if (event.changes() != null && event.changes().login() != null) {
            previousLogin = event.changes().login().from();
        }

        provisioningListener.onAccountRenamed(installationId, previousLogin, newLogin);
        upsertOrganization(event, installationId, newLogin);

        log.info(
                "Processed installation_target rename: installationId={}, previousLogin={}, newLogin={}",
                installationId,
                sanitizeForLog(previousLogin),
                sanitizeForLog(newLogin));
    }

    private void upsertOrganization(GitHubInstallationTargetEventDTO event, long installationId, String login) {
        if (!"Organization".equalsIgnoreCase(event.targetType())) {
            return;
        }

        var account = event.account();
        if (account == null || account.id() == null) {
            log.warn("Skipped organization upsert: reason=missingAccountDetails, installationId={}", installationId);
            return;
        }

        Long providerId = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"))
                .getId();
        organizationService.upsertIdentity(account.id(), login, Objects.requireNonNull(providerId));
    }
}
