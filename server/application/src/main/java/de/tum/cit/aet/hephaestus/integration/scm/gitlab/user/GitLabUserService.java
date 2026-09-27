package de.tum.cit.aet.hephaestus.integration.scm.gitlab.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for resolving GitLab users from webhook and GraphQL data.
 * <p>
 * Extracted from {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor}
 * to allow independent injection and reuse without requiring a processor instance.
 * <p>
 * Not conditional on GitLab being enabled because it is injected into
 * {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor}
 * which is always available (its subclasses handle webhook/sync events).
 */
@Service
public class GitLabUserService {

    private static final Logger log = LoggerFactory.getLogger(GitLabUserService.class);

    private static final String GET_USERS_BY_IDS_DOCUMENT = "GetUsersByIds";
    private static final String USER_GLOBAL_ID_PREFIX = "gid://gitlab/User/";

    /** The users a GitLab response reports, in the {@code GitLabUserFields} shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserNode(
            @Nullable String id,
            @Nullable String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl,
            @Nullable String publicEmail) {}

    private final UserRepository userRepository;
    private final GitLabProperties gitLabProperties;
    private final ObjectProvider<GitLabGraphQlClientProvider> graphQlClientProvider;
    private final ObjectProvider<GitLabGraphQlResponseHandler> responseHandler;

    public GitLabUserService(
            UserRepository userRepository,
            GitLabProperties gitLabProperties,
            ObjectProvider<GitLabGraphQlClientProvider> graphQlClientProvider,
            ObjectProvider<GitLabGraphQlResponseHandler> responseHandler) {
        this.userRepository = userRepository;
        this.gitLabProperties = gitLabProperties;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
    }

    /**
     * The profiles GitLab reports for {@code nativeIds} to the workspace's own credential. It only reads: a user GitLab
     * does not report is simply absent, and a response GitLab could not give throws, so a caller can retry.
     */
    public Map<Long, GitLabUserLookup> fetchCanonicalUsers(Long scopeId, Collection<Long> nativeIds) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(nativeIds));
        if (ids.isEmpty()) {
            return Map.of();
        }
        GitLabGraphQlClientProvider clients = graphQlClientProvider.getObject();
        GitLabGraphQlResponseHandler handler = responseHandler.getObject();
        Map<Long, GitLabUserLookup> users = new HashMap<>();
        for (int start = 0; start < ids.size(); start += GitLabSyncConstants.LARGE_PAGE_SIZE) {
            List<Long> page = ids.subList(start, Math.min(ids.size(), start + GitLabSyncConstants.LARGE_PAGE_SIZE));
            List<String> globalIds = new ArrayList<>(page.size());
            page.forEach(id -> globalIds.add(USER_GLOBAL_ID_PREFIX + id));
            clients.acquirePermission();
            ClientGraphQlResponse response = clients.forScope(scopeId)
                    .documentName(GET_USERS_BY_IDS_DOCUMENT)
                    .variable("ids", globalIds)
                    .variable("first", globalIds.size())
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());
            if (handler.handle(response, "users", log).action()
                            != GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE
                    || !Objects.requireNonNull(response).getErrors().isEmpty()
                    || response.field("users.nodes").getValue() == null) {
                GitLabSyncException failure = new GitLabSyncException("GitLab did not report the requested users");
                clients.recordFailure(failure);
                throw failure;
            }
            clients.recordSuccess();
            List<UserNode> nodes =
                    Objects.requireNonNull(response).field("users.nodes").toEntityList(UserNode.class);
            for (UserNode node : nodes) {
                String globalId = node.id();
                String username = node.username();
                if (globalId == null || username == null) {
                    continue;
                }
                long nativeId = GitLabSyncConstants.extractNumericId(globalId);
                if (page.contains(nativeId)) {
                    users.put(
                            nativeId,
                            new GitLabUserLookup(
                                    globalId,
                                    username,
                                    node.name(),
                                    node.avatarUrl(),
                                    node.webUrl(),
                                    node.publicEmail()));
                }
            }
        }
        return users;
    }

    /**
     * Resolves a GitLab avatar URL, prepending the server base URL for relative paths.
     * GitLab self-hosted instances return relative paths like {@code /uploads/-/system/user/avatar/123/avatar.png}.
     */
    private String resolveAvatarUrl(@Nullable String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            return "";
        }
        if (avatarUrl.startsWith("/")) {
            return gitLabProperties.defaultServerUrl() + avatarUrl;
        }
        return avatarUrl;
    }

    /**
     * Finds or creates a user from webhook data.
     * <p>
     * Stores the raw GitLab user ID as {@code nativeId} with the given {@code providerId}.
     * Constructs HTML URL from the GitLab server URL and username.
     */
    @Nullable
    public User findOrCreateUser(@Nullable GitLabWebhookUser dto, Long providerId) {
        // On a connection route the body is only as trustworthy as the group owner who could shape it; a user row is
        // shared by every workspace, so it is written only as GitLab reported it to that connection.
        if (GitLabRouteAdmission.current().isPresent()) {
            return dto == null || dto.id() == null ? null : storeReportedUser(dto.id(), providerId);
        }
        if (dto == null || dto.id() == null || dto.username() == null) {
            return null;
        }

        long nativeId = dto.id();
        String login = dto.username();
        String name = dto.name() != null ? dto.name() : login;
        String avatarUrl = resolveAvatarUrl(dto.avatarUrl());
        String htmlUrl = gitLabProperties.defaultServerUrl() + "/" + login;

        userRepository.upsertUser(
                nativeId,
                providerId,
                login,
                name,
                avatarUrl,
                htmlUrl,
                GitLabUserClassifier.classify(login).name(),
                dto.email(),
                null, // createdAt — not in webhook
                null // updatedAt — not in webhook
                );

        return userRepository.findByNativeIdAndProviderId(nativeId, providerId).orElse(null);
    }

    /**
     * The user {@code nativeId} as GitLab reported it for the delivery being handled on a connection route, stored with
     * that profile; {@code null} when GitLab did not report the user. A stale row that still holds the reported login
     * gives it up, as in the group member sync. Joins the caller's write transaction, which holds the connection's
     * lifecycle lock, so the login lock, the freed login and the stored profile commit with the handler's writes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Nullable
    public User findOrCreateReportedUser(long nativeId, Long providerId) {
        return storeReportedUser(nativeId, providerId);
    }

    private @Nullable User storeReportedUser(long nativeId, Long providerId) {
        Optional<GitLabUserLookup> reported = GitLabRouteAdmission.reportedUser(nativeId);
        String login = reported.map(GitLabUserLookup::username).orElse(null);
        if (reported.isEmpty() || login == null) {
            log.warn("Skipped user: reason=notReportedByGitLab, userId={}", nativeId);
            return null;
        }
        if (userRepository.tryAcquireLoginLock(login, providerId)) {
            userRepository.freeLoginConflicts(login, nativeId, providerId);
        }
        return upsertLookup(reported.get(), providerId);
    }

    /**
     * Finds or creates a user from GraphQL data.
     * <p>
     * {@code @Transactional} is required because the underlying {@code upsertUser} is a
     * {@code @Modifying} native query. Callers with access to the {@code GitLabUserFields}
     * GraphQL fragment should populate {@link GitLabUserLookup#publicEmail()} so that
     * downstream commit-author resolution can match by email.
     */
    @Transactional
    @Nullable
    public User findOrCreateUser(GitLabUserLookup lookup, Long providerId) {
        return upsertLookup(lookup, providerId);
    }

    private @Nullable User upsertLookup(GitLabUserLookup lookup, Long providerId) {
        if (lookup == null || lookup.globalId() == null || lookup.username() == null) {
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(lookup.globalId());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped user resolution: reason=invalidGlobalId, gid={}", lookup.globalId());
            return null;
        }

        String username = lookup.username();
        String resolvedName = lookup.name() != null ? lookup.name() : username;
        String resolvedAvatarUrl = resolveAvatarUrl(lookup.avatarUrl());
        String resolvedHtmlUrl =
                lookup.webUrl() != null ? lookup.webUrl() : (gitLabProperties.defaultServerUrl() + "/" + username);
        String resolvedEmail =
                (lookup.publicEmail() != null && !lookup.publicEmail().isBlank()) ? lookup.publicEmail() : null;

        userRepository.upsertUser(
                nativeId,
                providerId,
                username,
                resolvedName,
                resolvedAvatarUrl,
                resolvedHtmlUrl,
                GitLabUserClassifier.classify(username).name(),
                resolvedEmail,
                null, // createdAt — not in GraphQL user data
                null // updatedAt — not in GraphQL user data
                );

        return userRepository.findByNativeIdAndProviderId(nativeId, providerId).orElse(null);
    }
}
