package de.tum.cit.aet.hephaestus.integration.scm.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts.ThreadFacts;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmMemberRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ActivityScmFactsAdapterTest extends BaseUnitTest {
    @Mock
    private UserRepository users;

    @Mock
    private RepositoryRepository repositories;

    @Mock
    private ScmMemberRepository members;

    @Mock
    private TeamRepository teams;

    @Mock
    private PullRequestReviewThreadRepository threads;

    private ActivityScmFactsAdapter facts;

    @BeforeEach
    void setUp() {
        facts = new ActivityScmFactsAdapter(users, repositories, members, teams, threads);
    }

    @Test
    void shouldReportResolverAuthorAndRepositoryWhenTheThreadHasThem() {
        var pullRequest = pullRequest(user(10L), repository(20L));
        when(threads.findById(1L)).thenReturn(Optional.of(thread(pullRequest, user(30L))));

        assertThat(facts.reviewThread(1L)).contains(new ThreadFacts(20L, 30L, 10L));
    }

    @Test
    void shouldLeaveOutWhatTheThreadLacksWhenItsPullRequestIsIncomplete() {
        when(threads.findById(1L)).thenReturn(Optional.of(thread(pullRequest(null, null), null)));
        when(threads.findById(2L)).thenReturn(Optional.of(thread(null, user(30L))));

        assertThat(facts.reviewThread(1L)).contains(new ThreadFacts(null, null, null));
        assertThat(facts.reviewThread(2L)).contains(new ThreadFacts(null, 30L, null));
        assertThat(facts.reviewThread(3L)).isEmpty();
    }

    private static PullRequestReviewThread thread(@Nullable PullRequest pullRequest, @Nullable User resolvedBy) {
        var thread = new PullRequestReviewThread();
        thread.setPullRequest(pullRequest);
        thread.setResolvedBy(resolvedBy);
        return thread;
    }

    private static PullRequest pullRequest(@Nullable User author, @Nullable Repository repository) {
        var pullRequest = new PullRequest();
        pullRequest.setAuthor(author);
        pullRequest.setRepository(repository);
        return pullRequest;
    }

    private static User user(long id) {
        var user = new User();
        user.setId(id);
        return user;
    }

    private static Repository repository(long id) {
        var repository = new Repository();
        repository.setId(id);
        return repository;
    }
}
