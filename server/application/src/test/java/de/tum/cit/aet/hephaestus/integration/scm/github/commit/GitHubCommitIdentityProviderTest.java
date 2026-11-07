package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService.CommitActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GitHubCommitIdentityProviderTest extends BaseUnitTest {

    private static final String SHA = "a".repeat(40);
    private static final Question QUESTION = new Question(SHA, "someone@example.com");

    @Mock
    private CommitAuthorEnrichmentService commits;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubUserProcessor users;

    @Mock
    private PersonProcessingSuppression suppression;

    private GitHubCommitIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitHubCommitIdentityProvider(commits, graphQlClientProvider, users, suppression);
    }

    private static GitHubUserDTO account(long databaseId) {
        return new GitHubUserDTO(null, databaseId, "login" + databaseId, null, null, null, null, User.Type.USER);
    }

    private Map<Question, Answer> ask(List<CommitActor> actors) {
        when(commits.fetchCommitActors("o/r", 9L, List.of(SHA))).thenReturn(Map.of(SHA, actors));
        return provider.answer("o/r", 7L, 9L, Set.of(QUESTION));
    }

    @Test
    void shouldNameTheAccountOfTheActorWithTheEmail() {
        var local = new User();
        local.setId(3L);
        when(users.ensureExists(any(), any())).thenReturn(local);

        var answers = ask(List.of(
                new CommitActor("other@example.com", account(1L), true),
                new CommitActor("SOMEONE@example.com", account(2L), true)));

        assertThat(answers).containsEntry(QUESTION, new Answer.Account(3L, 2L));
    }

    @Test
    void shouldSayNoAccountWhenTheActorWithTheEmailHasNoUser() {
        var answers = ask(List.of(new CommitActor("someone@example.com", null, true)));

        assertThat(answers).containsEntry(QUESTION, new Answer.NoAccount());
    }

    @Test
    void shouldGiveNoAnswerWhenTheActorCouldNotBeRead() {
        var answers = ask(List.of(new CommitActor("someone@example.com", null, false)));

        assertThat(answers).isEmpty();
    }

    @Test
    void shouldGiveNoAnswerWhenNoActorHasTheEmail() {
        var answers = ask(List.of(new CommitActor("other@example.com", account(1L), true)));

        assertThat(answers).isEmpty();
    }

    @Test
    void shouldGiveNoAnswerWhenActorsWithTheEmailDisagree() {
        var answers = ask(List.of(
                new CommitActor("someone@example.com", account(1L), true),
                new CommitActor("someone@example.com", null, true)));

        assertThat(answers).isEmpty();
    }

    @Test
    void shouldNotCreateAUserForAnErasedAccount() {
        when(suppression.isSuppressed(7L, "2", null)).thenReturn(true);

        var answers = ask(List.of(new CommitActor("someone@example.com", account(2L), true)));

        assertThat(answers).containsEntry(QUESTION, new Answer.Erased());
        verify(users, never()).ensureExists(any(), any());
    }

    @Test
    void shouldGiveNoAnswerWhenGitHubDoesNotReturnTheCommit() {
        when(commits.fetchCommitActors("o/r", 9L, List.of(SHA))).thenReturn(Map.of());

        assertThat(provider.answer("o/r", 7L, 9L, Set.of(QUESTION))).isEmpty();
    }

    @Test
    void shouldDeferWhenTheRateLimitStoppedTheRead() {
        when(commits.fetchCommitActors("o/r", 9L, List.of(SHA))).thenReturn(Map.of());
        when(graphQlClientProvider.isRateLimitCritical(anyLong())).thenReturn(true);

        assertThat(provider.answer("o/r", 7L, 9L, Set.of(QUESTION))).containsEntry(QUESTION, new Answer.Deferred());
        verify(suppression, never()).isSuppressed(anyLong(), anyString(), any());
    }
}
