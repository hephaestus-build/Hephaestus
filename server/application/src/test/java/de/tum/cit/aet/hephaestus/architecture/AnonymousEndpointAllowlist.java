package de.tum.cit.aet.hephaestus.architecture;

import java.util.Set;

final class AnonymousEndpointAllowlist {
    static final Set<String> HANDLERS = Set.of(
            "PublicActivityController.getPublicActivity",
            "ContributorController.listGlobalContributors",
            "WorkspaceRegistryController.getProviders",
            "EmailUnsubscribeController.unsubscribe",
            "WorkerTokenExchangeController.exchange",
            "SlackInteractivityController.interactivity",
            "AuthBeginController.begin",
            "AuthBeginController.beginClient",
            "WellKnownController.jwks",
            "ClientSessionController.configuration",
            "ClientSessionController.token",
            "ClientSessionController.refresh",
            "ClientSessionController.logout",
            "DevLoginController.devLogin",
            "DevLoginController.devClientLogin",
            "OAuthCallbackController.callbackGet",
            "OAuthCallbackController.callbackPost",
            "WebhookController.ingest",
            "IdentityProviderDiscoveryController.list",
            "GitLabConnectionWebhookController.ingest");

    private AnonymousEndpointAllowlist() {}
}
