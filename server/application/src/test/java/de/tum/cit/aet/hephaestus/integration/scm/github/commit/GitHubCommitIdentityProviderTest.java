package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.CredentialGeneration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Pause;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService.CommitActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
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
    private ActiveScm credential;

    @Mock
    private GitHubUserProcessor users;

    @Mock
    private PersonProcessingSuppression suppression;

    private GitHubCommitIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitHubCommitIdentityProvider(commits, users, suppression);
    }

    private static GitHubUserDTO account(long databaseId) {
        return new GitHubUserDTO(null, databaseId, "login" + databaseId, null, null, null, null, User.Type.USER);
    }

    private Map<Question, Answer> ask(List<CommitActor> actors) {
        when(commits.fetchCommitActors("o/r", 9L, credential, List.of(SHA)))
                .thenReturn(new CommitAuthorEnrichmentService.CommitActors(Map.of(SHA, actors), null));
        return provider.answer("o/r", 7L, 9L, credential, Set.of(QUESTION)).answers();
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
        when(commits.fetchCommitActors("o/r", 9L, credential, List.of(SHA)))
                .thenReturn(new CommitAuthorEnrichmentService.CommitActors(Map.of(), null));

        assertThat(provider.answer("o/r", 7L, 9L, credential, Set.of(QUESTION)).answers())
                .isEmpty();
    }

    @Test
    void shouldDeferEveryUnansweredCommitOfAThrottledReadThoughTheQuotaLooksFree() {
        // A secondary limit, or a throttle answered with HTTP 200, while the tracked primary quota still has room.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        when(commits.fetchCommitActors("o/r", 9L, credential, List.of(SHA)))
                .thenReturn(new CommitAuthorEnrichmentService.CommitActors(Map.of(), deadline));

        var batch = provider.answer("o/r", 1L, 9L, credential, Set.of(QUESTION));
        assertThat(batch.answers()).isEmpty();
        assertThat(batch.pause()).isEqualTo(new Pause(deadline, new CredentialGeneration(0L, 0L)));
    }

    @Test
    void shouldDeferAnEmailThatAThrottledCommitLeftWithoutAnAnswerAndAnswerTheNamedOne() {
        var local = new User();
        local.setId(3L);
        when(users.ensureExists(any(), any())).thenReturn(local);
        Question committer = new Question(SHA, "committer@example.com");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        // Under the throttle, the committer's null user is no answer, and the author's account is one.
        when(commits.fetchCommitActors("o/r", 9L, credential, List.of(SHA)))
                .thenReturn(new CommitAuthorEnrichmentService.CommitActors(
                        Map.of(
                                SHA,
                                List.of(
                                        new CommitActor("someone@example.com", account(2L), true),
                                        new CommitActor("committer@example.com", null, false))),
                        deadline));

        var mixed = provider.answer("o/r", 7L, 9L, credential, new LinkedHashSet<>(List.of(QUESTION, committer)));
        var answered = provider.answer("o/r", 7L, 9L, credential, Set.of(QUESTION));
        assertThat(mixed.answers()).containsExactlyEntriesOf(Map.of(QUESTION, new Answer.Account(3L, 2L)));
        assertThat(answered.answers()).isEqualTo(mixed.answers());
        assertThat(mixed.pause()).isEqualTo(new Pause(deadline, new CredentialGeneration(0L, 0L)));
        assertThat(answered.pause()).isEqualTo(mixed.pause());
    }
}
