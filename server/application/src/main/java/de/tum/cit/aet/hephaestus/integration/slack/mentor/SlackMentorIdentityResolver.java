package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Objects;
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
public class SlackMentorIdentityResolver {

    /** The canonical Slack server URL the seeded {@code identity_provider} row is keyed by. */
    static final String SLACK_SERVER_URL = "https://slack.com";

    private final GitProviderRegistry gitProviderRegistry;
    private final AccountIdentityQuery accountIdentityQuery;
    private final AccountWorkspaceMembershipQuery workspaceMembershipQuery;
    private final UserRepository userRepository;

    public SlackMentorIdentityResolver(
            GitProviderRegistry gitProviderRegistry,
            AccountIdentityQuery accountIdentityQuery,
            AccountWorkspaceMembershipQuery workspaceMembershipQuery,
            UserRepository userRepository) {
        this.gitProviderRegistry = gitProviderRegistry;
        this.accountIdentityQuery = accountIdentityQuery;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
        this.userRepository = userRepository;
    }

    /** Resolves the display actor for a live interaction; requires an active account and workspace membership. */
    @Transactional(readOnly = true)
    public Optional<User> resolveDeveloper(long workspaceId, @Nullable String teamId, String slackUserId) {
        return resolveMemberIdInternal(workspaceId, teamId, slackUserId, true).flatMap(userRepository::findById);
    }

    /** Resolves a historical author without requiring a currently active account. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveMemberId(long workspaceId, @Nullable String teamId, String slackUserId) {
        return resolveMemberIdInternal(workspaceId, teamId, slackUserId, false);
    }

    /**
     * The account's member in a workspace.
     *
     * @param actorId the actor that speaks for the account in the workspace
     * @param accountActorIds every actor of the account that is a member of the workspace, {@code actorId} included
     */
    public record Member(long actorId, List<Long> accountActorIds) {}

    /** Live mentor admission requires an active account; historical attribution does not. */
    @Transactional(readOnly = true)
    public Optional<Member> resolveActiveMember(long workspaceId, @Nullable String teamId, String slackUserId) {
        return membership(workspaceId, teamId, slackUserId, true)
                .map(view -> new Member(Objects.requireNonNull(view.memberId()), view.memberIds()));
    }

    /** The account a Slack user is linked to, whatever its status: an account may always turn a notification off. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveAccountId(@Nullable String teamId, String slackUserId) {
        if (slackUserId.isBlank() || teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        return accountIdentityQuery.resolveAccountId(
                gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL), slackUserId, teamId);
    }

    private Optional<Long> resolveMemberIdInternal(
            long workspaceId, @Nullable String teamId, String slackUserId, boolean activeAccountOnly) {
        return membership(workspaceId, teamId, slackUserId, activeAccountOnly)
                .map(view -> Objects.requireNonNull(view.memberId()));
    }

    private Optional<AccountWorkspaceMembershipQuery.WorkspaceMembershipView> membership(
            long workspaceId, @Nullable String teamId, String slackUserId, boolean activeAccountOnly) {
        if (slackUserId.isBlank() || teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        long slackProviderId = gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL);
        Optional<Long> accountId = activeAccountOnly
                ? accountIdentityQuery.resolveActiveAccountId(slackProviderId, slackUserId, teamId)
                : accountIdentityQuery.resolveAccountId(slackProviderId, slackUserId, teamId);
        return accountId.map(workspaceMembershipQuery::membershipsForAccount).orElseGet(List::of).stream()
                .filter(view -> view.workspaceId() == workspaceId && view.memberId() != null)
                .findFirst();
    }

    /** Resolves an outbound recipient through the actor's provider subject and the connection's exact Slack team. */
    @Transactional(readOnly = true)
    public Optional<String> resolveSlackUserId(long memberId, @Nullable String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        long slackProviderId = gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL);
        return userRepository
                .findById(memberId)
                .flatMap(user -> accountIdentityQuery.resolveActiveAccountId(
                        Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null))
                .map(accountIdentityQuery::activeLinksForAccount)
                .orElseGet(List::of)
                .stream()
                .filter(link -> link.gitProviderId() != null && link.gitProviderId() == slackProviderId)
                .filter(link -> teamId.equals(link.teamId()))
                .map(AccountIdentityQuery.IdentityLinkView::subject)
                .filter(subject -> subject != null && !subject.isBlank())
                .findFirst();
    }
}
