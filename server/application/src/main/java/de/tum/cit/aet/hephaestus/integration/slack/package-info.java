/**
 * Slack vendor adapter — webhook + connect + lifecycle + messaging.
 *
 * <p>OPEN (matching {@code scm.github} / {@code scm.gitlab}): the
 * {@code messaging} named interface is consumed cross-module, the admin
 * controllers depend on {@code workspace::authorization}, and the {@code integration.core::*} sub-
 * surfaces are needed for credentials + connection + state. {@code
 * allowedDependencies} still pins the OUTBOUND boundary so this adapter cannot
 * silently grow new cross-module imports.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Slack",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "integration.core",
            "integration.core::events",
            "integration.core::spi",
            "integration.core::handler",
            "integration.core::oauth",
            "integration.core::consumer",
            "integration.core::webhook",
            // SlackChannelAdminController uses RequireAtLeastWorkspaceAdmin (authorization) and
            // resolves the workspace via @WorkspaceScopedController / WorkspaceContext (context).
            "workspace::authorization",
            "workspace::context",
            // SlackWorkspacePurgeAdapter implements the WorkspacePurgeContributor SPI so a workspace
            // PURGE cascades into a bulk delete of every Slack-owned table.
            "workspace::spi",
            "integration.scm",
            // Runtime-role gate (@ConditionalOnServerRole) on the connection-OAuth strategy.
            "core::runtime",
            // Shared core infra: @WorkspaceAgnostic on the inbound Slack-events controller (this path resolves the
            // workspace from the payload's team_id, not the URL).
            "core",
            // SlackChannelConsentService throws the shared EntityNotFoundException (→ 404) for an unknown /
            // cross-workspace channel.
            "core::exception",
            // SlackMessageService consults the instance-wide silent-mode brake before every outbound send.
            "core::settings-spi",
            // SlackMentorIdentityResolver resolves a Slack (team, user) to the workspace developer login through the
            // auth SPI ports only (GitProviderRegistry + AccountIdentityQuery + AccountWorkspaceMembershipQuery) —
            // never core.auth domain types.
            "core::auth-spi",
            // The Slack mentor adapter (integration.slack.mentor) runs a turn via the narrow MentorTurnRunner
            // port and streams through MentorChannel (propagate pulls in UIMessageChunk + MentorTurnRequest).
            "agent::mentor-chat",
            // SlackConversationNudgeService listens to the agent-published ConversationFeedbackPreparedEvent (a
            // type-level named interface: only the event record is exposed).
            "agent::conversation-nudge",
            // integration.slack.conversation implements the agent-owned conversation-source SPIs: Slack owns the
            // slack_thread/slack_message/slack_monitored_channel tables and projects them through these ports, so the
            // agent's read path carries no raw SQL against the Slack schema. One-way integration.slack -> agent edge,
            // so no bounded-context cycle forms.
            "agent::conversation-source",
            // SlackIngestService.eraseChannel completes the GDPR Art. 17 erasure of the
            // chat.conversation_thread-derived
            // observations/feedback via the practices-owned ConversationFeedbackErasure port (one-way edge, no cycle).
            "practices::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.slack;
