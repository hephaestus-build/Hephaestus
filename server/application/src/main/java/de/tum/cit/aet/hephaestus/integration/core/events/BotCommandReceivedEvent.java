package de.tum.cit.aet.hephaestus.integration.core.events;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import org.jspecify.annotations.Nullable;

/**
 * Published when a bot command (e.g. {@code /hephaestus review}) is detected on a pull-request / merge-request
 * comment. {@code ReviewCommandListener} in {@code practices/} handles it.
 *
 * <p>Vendor-neutral by contract: the publishing adapter resolves the comment to its workspace and canonical item,
 * so the subscriber never names a provider's repository or number. The GitHub and GitLab comment handlers publish it.
 *
 * @param kind           the integration kind of the publishing SCM
 * @param workspaceId    the workspace whose connection received the comment
 * @param itemId         that workspace's canonical item for the commented pull / merge request
 * @param noteBody       the raw comment body
 * @param noteAuthor     the login of the comment author, for logs only. A login is provider-scoped and the person
 *                       can change it, so nothing may be authorized on it — see {@code providerId} /
 *                       {@code authorNativeId}, which together are the identity the subscriber resolves the
 *                       commenter by
 * @param providerId     the git provider the comment arrived from, the first half of the commenter's stable identity
 * @param authorNativeId the provider's own numeric id for the comment author, the second half. A command from an
 *                       actor the publisher cannot name is not published at all, because a subscriber that spends
 *                       budget has to be able to ask who is asking
 * @param commentId      the comment's native id; a redelivered comment carries the same one
 * @param replyAddress   where an answer to the comment goes, in the publishing adapter's own form; null when the
 *                       payload names no thread to answer in
 */
public record BotCommandReceivedEvent(
        IntegrationKind kind,
        long workspaceId,
        long itemId,
        String noteBody,
        String noteAuthor,
        long providerId,
        long authorNativeId,
        long commentId,
        @Nullable String replyAddress) {}
