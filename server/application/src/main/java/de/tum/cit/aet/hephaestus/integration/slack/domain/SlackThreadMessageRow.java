package de.tum.cit.aet.hephaestus.integration.slack.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One projected, non-tombstoned turn of a Slack thread — the JPQL constructor-expression row
 * {@link SlackMessageRepository#findThreadMessages} returns to {@code SlackConversationProjector}. Author fields
 * fall back to {@code null} when the Slack sender never linked to a workspace member ({@code authorMemberId} is
 * {@code null}) — the {@code LEFT JOIN User} then contributes no row, so {@code authorLogin}/{@code authorName}
 * are {@code null} rather than failing the whole projection.
 */
public record SlackThreadMessageRow(
        String slackTs,
        String slackTeamId,
        @Nullable String authorSlackUserId,
        @Nullable Long authorMemberId,
        @Nullable String authorLogin,
        @Nullable String authorName,
        @Nullable String text,
        @Nullable Instant editedAt) {}
