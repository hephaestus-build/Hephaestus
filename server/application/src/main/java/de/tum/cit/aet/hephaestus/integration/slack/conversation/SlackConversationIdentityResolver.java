package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves Slack identities through verified account links and workspace membership. Slack subjects are
 * team-scoped: a missing team must never broaden a lookup. Live interactions require an active account;
 * historical attribution retains its separate semantics.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackConversationIdentityResolver {

    /** The canonical Slack server URL the seeded {@code identity_provider} row is keyed by. */
    static final String SLACK_SERVER_URL = "https://slack.com";

    private final GitProviderRegistry gitProviderRegistry;
    private final AccountIdentityQuery accountIdentityQuery;
    private final AccountMembershipQuery workspaceMembershipQuery;
    private final AccountSummaryQuery accounts;

    public SlackConversationIdentityResolver(
            GitProviderRegistry gitProviderRegistry,
            AccountIdentityQuery accountIdentityQuery,
            AccountMembershipQuery workspaceMembershipQuery,
            AccountSummaryQuery accounts) {
        this.gitProviderRegistry = gitProviderRegistry;
        this.accountIdentityQuery = accountIdentityQuery;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
        this.accounts = accounts;
    }

    /** Resolves the account summary for a live interaction; requires an active account and workspace membership. */
    @Transactional(readOnly = true)
    public Optional<AccountSummaryQuery.AccountSummary> resolveDeveloper(
            long workspaceId, @Nullable String teamId, String slackUserId) {
        return resolveAccountIdInternal(workspaceId, teamId, slackUserId, true)
                .map(accountId -> accounts.findAllByIds(List.of(accountId)).get(accountId));
    }

    /** Resolves a historical author without requiring a currently active account. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveAccountId(long workspaceId, @Nullable String teamId, String slackUserId) {
        return resolveAccountIdInternal(workspaceId, teamId, slackUserId, false);
    }

    /** Live conversation admission requires an active account; historical attribution does not. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveActiveAccountId(long workspaceId, @Nullable String teamId, String slackUserId) {
        return resolveAccountIdInternal(workspaceId, teamId, slackUserId, true);
    }

    /**
     * The account a Slack user is linked to, whatever its status or memberships: an account may always turn a
     * notification off.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolveLinkedAccountId(@Nullable String teamId, String slackUserId) {
        if (slackUserId.isBlank() || teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        return accountIdentityQuery.resolveAccountId(
                gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL), slackUserId, teamId);
    }

    private Optional<Long> resolveAccountIdInternal(
            long workspaceId, @Nullable String teamId, String slackUserId, boolean activeAccountOnly) {
        if (slackUserId.isBlank() || teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        long slackProviderId = gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL);
        Optional<Long> accountId = activeAccountOnly
                ? accountIdentityQuery.resolveActiveAccountId(slackProviderId, slackUserId, teamId)
                : accountIdentityQuery.resolveAccountId(slackProviderId, slackUserId, teamId);
        return accountId.filter(id -> workspaceMembershipQuery.membershipsForAccount(id).stream()
                .anyMatch(view -> view.workspaceId() == workspaceId));
    }

    /** Resolves an outbound recipient through the account's provider subject and the connection's exact Slack team. */
    @Transactional(readOnly = true)
    public Optional<String> resolveSlackUserId(long accountId, @Nullable String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        long slackProviderId = gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL);
        return accountIdentityQuery.activeLinksForAccount(accountId).stream()
                .filter(link -> link.providerId() == slackProviderId)
                .filter(link -> teamId.equals(link.teamKey()))
                .map(AccountIdentityQuery.IdentityView::providerUserKey)
                .filter(subject -> !subject.isBlank())
                .filter(subject -> accountIdentityQuery
                        .resolveActiveAccountId(slackProviderId, subject, teamId)
                        .filter(id -> id == accountId)
                        .isPresent())
                .findFirst();
    }
}
