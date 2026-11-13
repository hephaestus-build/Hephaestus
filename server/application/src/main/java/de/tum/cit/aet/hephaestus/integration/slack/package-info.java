/** Slack connections, consent, messages and review capture. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Slack",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            // Heph's Slack surface asks Heph's admission (MentorAdmission) before it writes a question, and its
            // projector reports what reached the developer (MentorTurnCompletion).
            "agent::mentor-turn",
            // The weekly digest's Slack note: the event it answers, the digest it names and the account's choice,
            // which its Turn off button changes.
            "agent::weekly-digest",
            // Shared core infra: @WorkspaceAgnostic on the inbound Slack-events controller (this path resolves the
            // workspace from the payload's team_id, not the URL).
            "core",
            // Hephaestus's Slack posts are outbox actions.
            "core::action",
            "core::auth-reference",
            // SlackMentorIdentityResolver resolves a Slack (team, user) to the workspace developer login through the
            // auth SPI ports only (GitProviderRegistry + AccountIdentityQuery + AccountMembershipQuery) —
            // never core.auth domain types.
            "core::auth-spi",
            // A paused or revoked channel tells running Heph turns that their context changed.
            "core::event",
            // SlackChannelConsentService throws the shared EntityNotFoundException (→ 404) for an unknown /
            // cross-workspace channel.
            "core::exception",
            "core::person-data",
            // Runtime-role gate (@ConditionalOnServerRole) on the Slack beans that run only in the server role.
            "core::runtime",
            "core::security",
            "core::web",
            "evidence",
            "evidence::capture",
            "integration.chat::item",
            "integration.core",
            "integration.core::consumer",
            "integration.core::events",
            "integration.core::framework",
            "integration.core::handler",
            "integration.core::items",
            "integration.core::spi",
            // Heph's Slack surface writes questions, edits, deletions, stops and votes into the developer's
            // conversation ledger, projects Slack turns into the DM, and erases Slack-written entries on a disconnect,
            // an uninstall or a revoked token.
            "mentor",
            // SlackIngestService.eraseChannel completes the GDPR Art. 17 erasure of the results and feedback derived
            // from chat.conversation_thread items through the practices-owned ConversationFeedbackErasure port
            // (one-way edge, no cycle). The App Home and the DM ingress ask MentorReadinessQuery whether Heph is ready.
            "practices::spi",
            // SlackChannelAdminController uses RequireAtLeastWorkspaceAdmin (authorization) and
            // resolves the workspace via @WorkspaceScopedController / WorkspaceContext (context).
            "workspace::authorization",
            "workspace::context",
            // SlackWorkspacePurgeAdapter implements the WorkspacePurgeContributor SPI so a workspace
            // PURGE cascades into a bulk delete of every Slack-owned table.
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.slack;
