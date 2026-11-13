package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener.AccountType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener.InstallationData;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener.RepositorySnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationEventDTO;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub installation webhook events and provisions scopes.
 */
@Component
@ConditionalOnServerRole
public class GitHubInstallationMessageHandler extends AbstractIntegrationMessageHandler<GitHubInstallationEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final ProvisioningListener provisioningListener;
    private final OrganizationService organizationService;
    private final GitHubAppTokenService gitHubAppTokenService;
    private final IdentityProviderRepository gitProviderRepository;

    GitHubInstallationMessageHandler(
            ProvisioningListener provisioningListener,
            OrganizationService organizationService,
            GitHubAppTokenService gitHubAppTokenService,
            IdentityProviderRepository gitProviderRepository,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "installation." + GitHubEventType.INSTALLATION.getValue(),
                GitHubInstallationEventDTO.class,
                deserializer,
                transactionTemplate);
        this.provisioningListener = provisioningListener;
        this.organizationService = organizationService;
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.gitProviderRepository = gitProviderRepository;
    }

    /** Every action handles here: the default dispatch would hold its transaction across the GitHub status read. */
    @Override
    protected void dispatchEvent(GitHubInstallationEventDTO event) {
        handleEvent(event);
    }

    /**
     * Reads GitHub's current installation status first, with no transaction open, then changes the workspace in one
     * short transaction. A slow GitHub API therefore holds no database connection and no workspace row lock.
     */
    @Override
    protected void handleEvent(GitHubInstallationEventDTO event) {
        var installation = event.installation();
        if (installation == null) {
            log.warn("Received installation event with missing data: action={}", event.action());
            return;
        }
        GitHubEventAction.Installation action = event.actionType();
        ProviderStatus status = readsStatus(action) ? readStatus(installation.id(), action) : ProviderStatus.NOT_READ;
        handleOnce(() -> apply(event, installation, action, status));
    }

    /** GitHub's installation status, as read before the transaction. */
    private enum ProviderStatus {
        NOT_READ,
        ACTIVE,
        SUSPENDED,
        GONE,
        UNKNOWN
    }

    /** The webhook action is only a trigger for these: the current state comes from the API. */
    private static boolean readsStatus(GitHubEventAction.Installation action) {
        return action == GitHubEventAction.Installation.CREATED
                || action == GitHubEventAction.Installation.SUSPEND
                || action == GitHubEventAction.Installation.UNSUSPEND;
    }

    private ProviderStatus readStatus(Long installationId, GitHubEventAction.Installation action) {
        try {
            return gitHubAppTokenService.isInstallationSuspended(installationId)
                    ? ProviderStatus.SUSPENDED
                    : ProviderStatus.ACTIVE;
        } catch (InstallationNotFoundException e) {
            return ProviderStatus.GONE;
        } catch (RuntimeException e) {
            DeliveryInbox.rethrowRejectedWrite();
            // Network errors, credentials not configured, or other transient issues.
            if (action == GitHubEventAction.Installation.CREATED) {
                // Proceed with activation: if the installation is truly suspended, token minting fails fast.
                log.warn(
                        "Could not verify installation status for created event, proceeding with activation:"
                                + " installationId={}, error={}",
                        installationId,
                        e.getClass().getSimpleName());
            } else {
                log.warn(
                        "Failed to verify installation status via API, skipping status update: installationId={},"
                                + " error={}",
                        installationId,
                        e.getClass().getSimpleName());
            }
            return ProviderStatus.UNKNOWN;
        }
    }

    private void apply(
            GitHubInstallationEventDTO event,
            GitHubInstallationEventDTO.GitHubInstallationDTO installation,
            GitHubEventAction.Installation action,
            ProviderStatus status) {
        var account = installation.account();
        String accountLogin = account != null ? account.login() : null;
        Long installationId = installation.id();

        log.debug("Received installation event: action={}, installationId={}", event.action(), installationId);

        // Handle deletion early - no scope provisioning needed
        if (action == GitHubEventAction.Installation.DELETED) {
            log.info("Processed installation deletion: installationId={}", installationId);
            provisioningListener.onInstallationDeleted(installationId);
            return;
        }

        // For SUSPEND/UNSUSPEND: sync the verified current state only, without full provisioning.
        if (action == GitHubEventAction.Installation.SUSPEND || action == GitHubEventAction.Installation.UNSUSPEND) {
            applyVerifiedStatus(installationId, status);
            return;
        }

        // For CREATED and other events: do full provisioning
        String repositorySelection = installation.repositorySelection();
        String avatarUrl = account != null ? account.avatarUrl() : null;
        Long accountId = account != null ? account.id() : null;
        AccountType accountType = account != null && "Organization".equalsIgnoreCase(account.type())
                ? AccountType.ORGANIZATION
                : AccountType.USER;

        // Extract repository snapshots from the installation event payload
        // These are provided for "created" events with "selected" repository selection
        List<RepositorySnapshot> repositories = event.repositories() != null
                ? event.repositories().stream()
                        .map(ref -> new RepositorySnapshot(
                                Objects.requireNonNull(ref.id()), ref.fullName(), ref.name(), ref.isPrivate()))
                        .toList()
                : Collections.emptyList();

        InstallationData installationData =
                new InstallationData(installationId, accountId, accountLogin, accountType, avatarUrl, repositories);

        // A replayed "created" event must not reactivate an installation that is suspended now.
        if (status == ProviderStatus.GONE) {
            log.info(
                    "Installation no longer exists on GitHub, skipping workspace creation: installationId={}",
                    installationId);
            return;
        }
        if (status == ProviderStatus.SUSPENDED) {
            log.info(
                    "Installation created event received but installation is currently suspended, marking suspended:"
                            + " installationId={}",
                    installationId);
            gitHubAppTokenService.markInstallationSuspended(installationId);
            // Still create the workspace but don't activate it
            provisioningListener.onInstallationCreated(installationData);
            provisioningListener.onRepositorySelectionChanged(installationId, repositorySelection);
            provisioningListener.onInstallationSuspended(installationId);
            return;
        }

        provisioningListener.onInstallationCreated(installationData);
        provisioningListener.onRepositorySelectionChanged(installationId, repositorySelection);

        // Ensure organization identity is up-to-date if applicable
        if (account != null && "Organization".equalsIgnoreCase(account.type())) {
            Long providerId = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                    .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"))
                    .getId();
            organizationService.upsertIdentity(
                    Objects.requireNonNull(account.id()),
                    Objects.requireNonNull(accountLogin),
                    Objects.requireNonNull(providerId));
        }

        // Handle activation for CREATED events
        if (action == GitHubEventAction.Installation.CREATED) {
            provisioningListener.onInstallationActivated(installationId);
        }
    }

    /** Updates the workspace to GitHub's verified status; a status that could not be read changes nothing. */
    private void applyVerifiedStatus(Long installationId, ProviderStatus status) {
        switch (status) {
            case SUSPENDED -> {
                log.info("Verified installation status via API: installationId={}, status=SUSPENDED", installationId);
                // Mark in-memory FIRST to immediately block all running threads from minting tokens
                gitHubAppTokenService.markInstallationSuspended(installationId);
                provisioningListener.onInstallationSuspended(installationId);
            }
            case ACTIVE -> {
                log.info("Verified installation status via API: installationId={}, status=ACTIVE", installationId);
                gitHubAppTokenService.markInstallationActive(installationId);
                provisioningListener.onInstallationActivated(installationId);
            }
            case GONE ->
                log.info("Installation no longer exists, skipping status update: installationId={}", installationId);
            case UNKNOWN, NOT_READ -> {
                // readStatus already logged why the status is unknown.
            }
        }
    }
}
