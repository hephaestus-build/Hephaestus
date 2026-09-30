package de.tum.cit.aet.hephaestus.integration.scm.gitlab.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * The account type recorded for GitLab users, on the rows the user writes leave: GitLab's own {@code bot} flag decides
 * it whatever the login, and a write that does not carry the flag keeps the type already stored.
 */
class GitLabUserTypeIntegrationTest extends BaseIntegrationTest {

    private static final String SERVER_URL = "https://gitlab.lrz.de";
    private static final long SERVICE_ACCOUNT = 90_393L;
    private static final long STUDENT = 18_024L;
    private static final long TOKEN_BOT = 88_212L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private GitLabUserService gitLabUserService;

    @Autowired
    private GitLabGraphQlResponseHandler responseHandler;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long providerId;

    @BeforeEach
    void provider() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                .orElseGet(() ->
                        identityProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, SERVER_URL)));
        providerId = Objects.requireNonNull(provider.getId());
    }

    @Test
    void shouldRecordTheTypeGitLabReportsWhateverTheLogin() {
        // An access-token bot recorded from its login alone, before GitLab said otherwise.
        store(new GitLabUserLookup(gid(TOKEN_BOT), "group_319719_bot_8abdb42c", "Token", null, null, null, null));
        assertThat(type(TOKEN_BOT)).isEqualTo(User.Type.BOT);

        Map<Long, GitLabUserLookup> reported = canonicalUsers(List.of(
                node(SERVICE_ACCOUNT, "heph_introcourse_tutor_e2e", true),
                node(STUDENT, "ga84xah", false),
                node(TOKEN_BOT, "group_319719_bot_8abdb42c", false)));
        reported.values().forEach(this::store);

        assertThat(type(SERVICE_ACCOUNT)).isEqualTo(User.Type.BOT);
        assertThat(type(STUDENT)).isEqualTo(User.Type.USER);
        assertThat(type(TOKEN_BOT)).isEqualTo(User.Type.USER);
    }

    @ParameterizedTest
    @CsvSource({
        "group_319719_bot_8abdb42c, false",
        "group_319719_bot_8abdb42c, true",
        "heph_introcourse_tutor_e2e, false",
        "heph_introcourse_tutor_e2e, true"
    })
    void shouldKeepTheTypeGitLabStatedThroughWritesThatDoNotSayItUntilGitLabStatesAnother(String login, boolean bot) {
        User.Type stated = bot ? User.Type.BOT : User.Type.USER;
        store(canonicalUsers(List.of(node(SERVICE_ACCOUNT, login, bot))).get(SERVICE_ACCOUNT));
        assertThat(type(SERVICE_ACCOUNT)).isEqualTo(stated);

        // A hook's user, and a read that did not carry the flag.
        transactionTemplate.executeWithoutResult(status -> gitLabUserService.findOrCreateUser(
                new GitLabWebhookUser(SERVICE_ACCOUNT, login, "Account", null, null), providerId));
        store(canonicalUsers(List.of(node(SERVICE_ACCOUNT, login, null))).get(SERVICE_ACCOUNT));
        // A member hook or listing without the flag, whose login-based guess applies only to a new user.
        transactionTemplate.executeWithoutResult(status -> userRepository.upsertUser(
                SERVICE_ACCOUNT,
                providerId,
                login,
                "Account",
                "",
                "",
                GitLabUserClassifier.nativeType(null),
                GitLabUserClassifier.insertionType(login, null),
                null,
                null,
                null));
        // A saved sign-in profile, which states no type.
        transactionTemplate.executeWithoutResult(status -> userRepository.upsertUser(
                SERVICE_ACCOUNT, providerId, login, "Account", "", "", null, null, null, null));
        assertThat(type(SERVICE_ACCOUNT)).isEqualTo(stated);

        store(canonicalUsers(List.of(node(SERVICE_ACCOUNT, login, !bot))).get(SERVICE_ACCOUNT));
        assertThat(type(SERVICE_ACCOUNT)).isEqualTo(bot ? User.Type.USER : User.Type.BOT);
    }

    @Test
    void shouldRecordAFirstSeenAccountWhoseTypeIsUnknownAsAUser() {
        transactionTemplate.executeWithoutResult(status -> gitLabUserService.findOrCreateUser(
                new GitLabWebhookUser(STUDENT, "ga84xah", "Student", null, null), providerId));

        assertThat(type(STUDENT)).isEqualTo(User.Type.USER);
    }

    /** What GitLab reports for {@code nodes} through the canonical user read, decoded as in production. */
    private Map<Long, GitLabUserLookup> canonicalUsers(List<Map<String, @Nullable Object>> nodes) {
        ClientGraphQlResponse response = GraphQlResponses.of(Map.of("users", Map.of("nodes", nodes)), List.of());
        GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
        when(clients.forScope(any())).thenReturn(ScriptedGraphQlClient.of(request -> Mono.just(response)));
        GitLabUserService service =
                new GitLabUserService(userRepository, gitLabProperties, provided(clients), provided(responseHandler));
        return service.fetchCanonicalUsers(
                1L,
                nodes.stream()
                        .map(node ->
                                GitLabSyncConstants.extractNumericId((String) Objects.requireNonNull(node.get("id"))))
                        .toList());
    }

    private void store(@Nullable GitLabUserLookup lookup) {
        transactionTemplate.executeWithoutResult(
                status -> gitLabUserService.findOrCreateUser(Objects.requireNonNull(lookup), providerId));
    }

    private User.Type type(long nativeId) {
        return userRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .orElseThrow()
                .getType();
    }

    /** A {@code GitLabUserFields} node; {@code bot} is left out when {@code null}, as a response that lacks it. */
    private static Map<String, @Nullable Object> node(long nativeId, String username, @Nullable Boolean bot) {
        Map<String, @Nullable Object> node = new HashMap<>();
        node.put("id", gid(nativeId));
        node.put("username", username);
        node.put("name", username);
        node.put("avatarUrl", null);
        node.put("webUrl", SERVER_URL + "/" + username);
        node.put("publicEmail", null);
        if (bot != null) {
            node.put("bot", bot);
        }
        return node;
    }

    private static String gid(long nativeId) {
        return "gid://gitlab/User/" + nativeId;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provided(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }
}
