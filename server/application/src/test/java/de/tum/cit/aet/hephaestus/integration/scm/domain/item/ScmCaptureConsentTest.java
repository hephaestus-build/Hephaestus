package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ScmCaptureConsentTest extends BaseUnitTest {
    @Test
    void shouldWithholdWhenAnotherContributorDeclinesRegardlessOfTheAboutHolder() {
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var copies = mock(PersonDataCopyRecorder.class);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        var author = person(provider, 11);
        var reviewer = person(provider, 22);
        when(identities.accountFor(new PersonIdentity(1L, "11", null))).thenReturn(Optional.of(101L));
        when(identities.accountFor(new PersonIdentity(1L, "22", null))).thenReturn(Optional.of(102L));
        when(accounts.find(101))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        101, Account.Status.ACTIVE, "Author", AiChoice.CLOUD, Instant.now(), true)));
        when(accounts.find(102))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        102, Account.Status.ACTIVE, "Reviewer", AiChoice.NO_AI, Instant.now(), true)));
        var consent = new ScmCaptureConsent(identities, accounts, suppression, copies);
        assertThatThrownBy(() -> consent.require(List.of(author, reviewer)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
    }

    @Test
    void shouldFailCaptureWhenContributorProviderIdentityIsUnavailable() {
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        var consent = new ScmCaptureConsent(
                mock(PersonIdentityResolver.class),
                mock(AccountDirectory.class),
                mock(PersonProcessingSuppression.class),
                mock(PersonDataCopyRecorder.class));

        assertThatThrownBy(() -> consent.require(List.of(person(provider, 11))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CAPTURE_FAILED));
    }

    @Test
    void shouldWithholdAnUnavailableAccountBeforeCopyingItsContributorIdentity() {
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var copies = mock(PersonDataCopyRecorder.class);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        when(identities.accountFor(new PersonIdentity(1L, "11", null))).thenReturn(Optional.of(101L));
        when(accounts.find(101))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        101, Account.Status.DELETING, "Author", AiChoice.CLOUD, Instant.now(), true)));
        var consent = new ScmCaptureConsent(identities, accounts, suppression, copies);
        assertThatThrownBy(() -> consent.require(List.of(person(provider, 11))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        when(accounts.find(101)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> consent.require(List.of(person(provider, 11))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        when(identities.accountFor(new PersonIdentity(1, "11", null)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> consent.require(List.of(person(provider, 11))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        verifyNoInteractions(copies);
    }

    @Test
    void shouldWithholdAnAccountWhoseOtherExactIdentityIsSuppressed() {
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var copies = mock(PersonDataCopyRecorder.class);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        when(identities.accountFor(new PersonIdentity(1L, "11", null))).thenReturn(Optional.of(101L));
        when(accounts.find(101))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        101, Account.Status.ACTIVE, "Author", AiChoice.CLOUD, Instant.now(), true)));
        when(suppression.isAccountSuppressed(101)).thenReturn(true);
        var consent = new ScmCaptureConsent(identities, accounts, suppression, copies);
        assertThatThrownBy(() -> consent.require(List.of(person(provider, 11))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        verifyNoInteractions(copies);
    }

    @Test
    void shouldDecideAndRecordARetainedFormerContributorByTheNativeIdentityTheOccasionKept() {
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var copies = mock(PersonDataCopyRecorder.class);
        // No mirror row: the person left the work, and only the occasion still names them.
        var former = new ScmCaptureConsent.Contributor(
                new PersonIdentity(1L, "33", null),
                new PersonCopyIdentity("GITLAB", "https://gitlab.example", "33", null));
        when(identities.accountFor(former.identity())).thenReturn(Optional.of(103L));
        when(accounts.find(103))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        103, Account.Status.ACTIVE, "Former", AiChoice.CLOUD, Instant.now(), true)));
        var consent = new ScmCaptureConsent(identities, accounts, suppression, copies);

        consent.requireContributors(List.of(former, former));
        verify(copies, times(1)).recordIdentity(former.copy());

        when(accounts.find(103))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        103, Account.Status.ACTIVE, "Former", AiChoice.NO_AI, Instant.now(), true)));
        assertThatThrownBy(() -> consent.requireContributors(List.of(former)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        when(suppression.isSuppressed(1L, "33", null)).thenReturn(true);
        assertThatThrownBy(() -> consent.requireContributors(List.of(former)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        verify(copies, times(1)).recordIdentity(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepCoreMetadataButNotCommitWordsWhenACommitContributorWithholdsConsent(IdentityProviderType kind) {
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var provider = new IdentityProvider(kind, "https://scm.example");
        provider.setId(1L);
        var author = person(provider, 11);
        author.setId(11L);
        var contributor = person(provider, 22);
        when(identities.accountFor(new PersonIdentity(1L, "11", null))).thenReturn(Optional.of(101L));
        when(identities.accountFor(new PersonIdentity(1L, "22", null))).thenReturn(Optional.of(102L));
        when(accounts.find(101))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        101, Account.Status.ACTIVE, "Author", AiChoice.CLOUD, Instant.now(), true)));
        when(accounts.find(102))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        102, Account.Status.ACTIVE, "Contributor", AiChoice.NO_AI, Instant.now(), true)));
        var users = mock(UserRepository.class);
        when(users.findAllById(List.<Long>of())).thenReturn(List.of());
        when(users.findAllById(List.of(22L))).thenReturn(List.of(contributor));
        var queries = mock(ScmContributorQueryRepository.class);
        when(queries.diffContributors(456)).thenReturn(List.of(22L));
        var consent = new ScmCaptureContributors(
                users,
                new ScmCaptureConsent(identities, accounts, suppression, mock(PersonDataCopyRecorder.class)),
                queries);
        var repository = new Repository();
        repository.setId(123L);
        repository.setNameWithOwner("owner/repo");
        repository.setProvider(provider);
        var work = new PullRequest();
        work.setId(456L);
        work.setAuthor(author);
        work.setRepository(repository);
        work.setTitle("Explain the change");
        work.setBody("This change repairs the example.");
        work.setNumber(42);
        work.setState(Issue.State.OPEN);
        work.setBaseRefName("main");
        work.setHeadRefName("feature");
        work.setHeadRefOid("b".repeat(40));
        work.setBaseRefOid("a".repeat(40));
        work.setHtmlUrl("https://scm.example/owner/repo/pull/42");
        var mapper = new ObjectMapper();
        var item = new ItemRef(99, 456, ScmItemEvents.PULL_REQUEST, 456L, null, null);
        var items = mock(ScmItemAccess.class);
        when(items.parent(item, ScmItemEvents.REPOSITORY))
                .thenReturn(new ItemRef(99, 123, ScmItemEvents.REPOSITORY, 123L, null, null));
        var access = mock(ScmRepositoryAccess.class);
        when(access.authorize(99, repository))
                .thenReturn(new IntegrationRef(IntegrationKind.valueOf(kind.name()), 99, null));
        var lookup = mock(ScmWorkLookup.class);
        when(lookup.find(item.kind(), 456L)).thenReturn(Optional.of(work));
        var pullRequests = mock(PullRequestRepository.class);
        when(pullRequests.findByIdForReviewContext(456L)).thenReturn(Optional.of(work));
        var git = mock(GitRepositoryManager.class);
        var preparer = mock(ReviewRepositoryPreparer.class);
        var content = new PullRequestContentSource(
                mapper,
                git,
                pullRequests,
                mock(PullRequestReviewCommentRepository.class),
                preparer,
                mock(DeliveredPullRequestCommentLookup.class));
        // The real capture owner applies consent; the sources this request does not select contribute nothing. A pull
        // request capture never asks the issue source.
        var general = mock(GeneralReviewCommentContentSource.class);
        var threads = mock(ReviewThreadContentSource.class);
        var linked = mock(LinkedWorkItemContentSource.class);
        var tree = mock(RepositoryTreeContentSource.class);
        when(general.capture(any(), any())).thenReturn(CaptureContribution.empty());
        when(threads.capture(any(), any())).thenReturn(CaptureContribution.empty());
        when(linked.capture(any(), any())).thenReturn(CaptureContribution.empty());
        when(tree.capture(any(), any())).thenReturn(CaptureContribution.empty());
        var capture = new ScmWorkCapture(
                content,
                mock(IssueContentSource.class),
                general,
                threads,
                linked,
                tree,
                consent,
                mock(ItemEventService.class),
                mock(PersonDataWriteFence.class),
                mapper,
                Clock.systemUTC());
        var service = new ScmItemService(
                items,
                access,
                mock(RepositoryRepository.class),
                lookup,
                mock(PullRequestReviewRepository.class),
                mock(IssueEvidenceVersion.class),
                mock(ScmGitTree.class),
                mock(ScmFileVersions.class),
                mapper,
                capture,
                Clock.systemUTC());
        var pinned = service.pin(item, null);
        var core = PullRequestContentSource.CORE;
        var diff = PullRequestContentSource.DIFF;
        var request = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(core), "3.0.0", 3, null, Set.of(core, diff));
        var captured = service.capture(pinned, request);
        assertThat(captured.files())
                .containsKey("context/metadata.json")
                .doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
        verify(preparer, never()).prepare(any());
        verifyNoInteractions(git);
        var diffRequest = new CaptureRequest(request.reviewId(), request.jobId(), 0, Set.of(diff), "3.0.0", 3);
        assertThatThrownBy(() -> service.capture(pinned, diffRequest))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        error -> assertThat(error.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
    }

    private User person(IdentityProvider provider, long nativeId) {
        var user = new User();
        user.setProvider(provider);
        user.setNativeId(nativeId);
        user.setLogin("person-" + nativeId);
        user.setType(User.Type.USER);
        return user;
    }
}
