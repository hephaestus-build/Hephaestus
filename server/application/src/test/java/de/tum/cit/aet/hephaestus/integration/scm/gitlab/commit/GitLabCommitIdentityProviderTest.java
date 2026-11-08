package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabCommitIdentityProviderTest extends BaseUnitTest {
    private static final String SHA = "a".repeat(40);
    private static final Question QUESTION = new Question(SHA, "someone@example.com");

    @Mock
    private GitLabGraphQlClientProvider clients;

    @Mock
    private GitLabProperties properties;

    @Mock
    private GitLabUserService users;

    @Mock
    private PersonProcessingSuppression suppression;

    private GitLabCommitIdentityProvider provider;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        provider = new GitLabCommitIdentityProvider(clients, properties, users, suppression);
        lenient().when(clients.isCircuitClosed()).thenReturn(true);
        lenient().when(clients.getRateLimitRemaining(9L)).thenReturn(1000);
        lenient().when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
    }

    private Map<Question, Answer> ask(String body) {
        var web = WebClient.builder()
                .baseUrl("https://gitlab.example.test/api/graphql")
                .filter((request, next) -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(body)
                            .build());
                })
                .build();
        lenient()
                .when(clients.forScope(9L))
                .thenReturn(HttpGraphQlClient.builder(web)
                        .documentSource(new FragmentMergingDocumentSource(
                                List.of(new ClassPathResource("graphql/gitlab/operations/")),
                                List.of(".graphql"),
                                List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                        .build());
        return provider.answer("org/project", 7L, 9L, Set.of(QUESTION));
    }

    private static String commit(String fields) {
        return "{\"data\":{\"project\":{\"repository\":{\"commit\":{\"sha\":\"" + SHA + "\"," + fields + "}}}}}";
    }

    private static String actor() {
        return commit("\"authorEmail\":\"SOMEONE@example.com\",\"author\":{"
                + "\"id\":\"gid://gitlab/User/42\",\"username\":\"writer\",\"bot\":false}");
    }

    @Test
    void shouldResolveTheAuthorWithTheSameEmailThroughTheProviderId() {
        var local = new User();
        local.setId(3L);
        when(users.findOrCreateUser(any(GitLabUserLookup.class), anyLong())).thenReturn(local);
        assertThat(ask(actor())).containsEntry(QUESTION, new Answer.Account(3L, 42L));
        assertThat(calls).hasValue(1);
    }

    @Test
    void shouldClearOnlyWhenGitLabExplicitlyReportsNoAccountForTheEmail() {
        assertThat(ask(commit("\"authorEmail\":\"someone@example.com\",\"author\":null")))
                .containsEntry(QUESTION, new Answer.NoAccount());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"authorEmail\":\"someone@example.com\"",
                "\"authorEmail\":\"other@example.com\",\"author\":null",
                "\"authorEmail\":null,\"author\":null"
            })
    void shouldLeaveAMissingOrDifferentAuthorUnanswered(String fields) {
        assertThat(ask(commit(fields))).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldNotLinkAnErasedAccount() {
        when(suppression.isSuppressed(7L, "42", null)).thenReturn(true);
        assertThat(ask(actor())).containsEntry(QUESTION, new Answer.Erased());
        verifyNoInteractions(users);
    }

    @Test
    void shouldLeaveErrorsAndMissingCommitsUnanswered() {
        assertThat(ask("{\"data\":{\"project\":{\"repository\":{\"commit\":null}}}}"))
                .isEmpty();
        assertThat(ask("{\"errors\":[{\"message\":\"unavailable\"}]}")).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldDeferWithoutARequestWhenTheBudgetIsCritical() {
        when(clients.getRateLimitRemaining(9L)).thenReturn(0);
        assertThat(ask(actor())).containsEntry(QUESTION, new Answer.Deferred());
        assertThat(calls).hasValue(0);
        verifyNoInteractions(users);
    }

    @Test
    void shouldDeferWithoutARequestWhenTheCircuitIsOpen() {
        when(clients.isCircuitClosed()).thenReturn(false);
        assertThat(ask(actor())).containsEntry(QUESTION, new Answer.Deferred());
        assertThat(calls).hasValue(0);
    }
}
