/** Slack connections, consent, messages and review capture. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Slack",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "core::security",
            // Hephaestus's Slack posts are outbox actions.
            "core::action",
            // A paused or revoked channel tells running Heph turns that their context changed.
            "core::event",
            "integration.chat::item",
            "integration.docs::item",
            "evidence",
            "evidence::capture",
            "core::person-data",
            "integration.core",
            "integration.core::events",
            "integration.core::spi",
            "integration.core::framework",
            "integration.core::items",
            "integration.core::handler",
            "integration.core::oauth",
            "integration.core::consumer",
            "integration.core::webhook",
            // The interactivity Request URL bounds its payload with the webhook ingress filter, which reads the shared
            // WebhookProperties.
            "core::webhook",
            "core::web",
            // SlackChannelAdminController uses RequireAtLeastWorkspaceAdmin (authorization) and
            // resolves the workspace via @WorkspaceScopedController / WorkspaceContext (context).
            "workspace::authorization",
            "workspace::context",
            // SlackWorkspacePurgeAdapter implements the WorkspacePurgeContributor SPI so a workspace
            // PURGE cascades into a bulk delete of every Slack-owned table.
            "workspace::spi",
            // Runtime-role gate (@ConditionalOnServerRole) on the Slack beans that run only in the server role.
            "core::runtime",
            // One consent projection update or rebuild at a time, on every server.
            "core::lock",
            // The account-linking button opens the sign-in endpoint under the API path.
            "core::web",
            // Shared core infra: @WorkspaceAgnostic on the inbound Slack-events controller (this path resolves the
            // workspace from the payload's team_id, not the URL).
            "core",
            // SlackChannelConsentService throws the shared EntityNotFoundException (→ 404) for an unknown /
            // cross-workspace channel.
            "core::exception",
            // SlackMessageService consults the instance-wide silent-mode brake before every outbound send.
            "core::settings-spi",
            // SlackMentorIdentityResolver resolves a Slack (team, user) to the workspace developer login through the
            // auth SPI ports only (GitProviderRegistry + AccountIdentityQuery + AccountMembershipQuery) —
            // never core.auth domain types.
            "core::auth-spi",
            // Heph's Slack surface asks Heph's admission (MentorAdmission) before it writes a question, and its
            // projector reports what reached the developer (MentorTurnCompletion).
            "agent::mentor-turn",
            // The weekly digest's Slack note: the event it answers, the digest it names and the account's choice,
            // which its Turn off button changes.
            "agent::weekly-digest",
            // Heph's Slack surface writes questions, edits, deletions, stops and votes into the developer's
            // conversation ledger, projects Slack turns into the DM, and erases Slack-written entries on a disconnect,
            // an uninstall or a revoked token.
            "mentor",
            // SlackIngestService.eraseChannel completes the GDPR Art. 17 erasure of the results and feedback derived
            // from chat.conversation_thread items through the practices-owned ConversationFeedbackErasure port
            // (one-way edge, no cycle). The App Home and the DM ingress ask MentorReadinessQuery whether Heph is ready.
            "practices::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.slack;
