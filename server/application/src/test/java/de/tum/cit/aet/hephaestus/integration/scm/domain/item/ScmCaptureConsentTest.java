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
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
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
        when(lookup.current(item)).thenCallRealMethod();
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
                mock(GitRepositoryManager.class),
                preparer,
                lookup,
                mock(ScmRetainedOccasionEvidence.class),
                Clock.systemUTC());
        var service = new ScmItemService(
                items,
                access,
                mock(RepositoryRepository.class),
                lookup,
                mock(IssueEvidenceVersion.class),
                mock(ScmGitTree.class),
                mock(ScmFileVersions.class),
                mapper,
                capture,
                mock(ScmRetainedOccasionEvidence.class),
                new ScmItemTransactions(),
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

    /** The batch's mocks: contributor 11 with account 101, ACTIVE with {@code choice}, and the consent under test. */
    private record Batch(
            PersonIdentityResolver identities,
            AccountDirectory accounts,
            PersonDataCopyRecorder copies,
            ScmCaptureConsent consent,
            ScmCaptureConsent.Contributor contributor) {
        static Batch of(AiChoice choice) {
            var identities = mock(PersonIdentityResolver.class);
            var accounts = mock(AccountDirectory.class);
            var copies = mock(PersonDataCopyRecorder.class);
            var identity = new PersonIdentity(1L, "11", null);
            when(identities.accountFor(identity)).thenReturn(Optional.of(101L));
            // A row whose identity check fails never reads the account.
            Mockito.lenient().when(accounts.find(101)).thenReturn(Optional.of(account(choice)));
            return new Batch(
                    identities,
                    accounts,
                    copies,
                    new ScmCaptureConsent(identities, accounts, mock(PersonProcessingSuppression.class), copies),
                    new ScmCaptureConsent.Contributor(identity, copy("https://github.com")));
        }

        static AccountDirectory.AccountView account(AiChoice choice) {
            return new AccountDirectory.AccountView(101, Account.Status.ACTIVE, "Author", choice, Instant.now(), true);
        }

        static PersonCopyIdentity copy(String origin) {
            return new PersonCopyIdentity("GITHUB", origin, "11", null);
        }

        void require() {
            consent.requireContributors(List.of(contributor));
        }

        /** The sources of one capture of several sources, as {@code ScmItemService#captureEach} runs them. */
        void inBatch(Runnable sources) {
            var opened = ScmCaptureConsent.openBatch(true);
            try {
                sources.run();
                ScmCaptureConsent.settleBatch(opened);
            } finally {
                ScmCaptureConsent.closeBatch(opened);
            }
        }

        void decidedTimes(int times) {
            verify(identities, times(times)).accountFor(contributor.identity());
        }
    }

    private static void assertWithheld(Runnable check) {
        assertThatThrownBy(check::run)
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
    }

    /** Row 1: outside a batch, as in every review capture, each check decides again. */
    @Test
    void shouldDecideEachCheckOutsideABatch() {
        var batch = Batch.of(AiChoice.CLOUD);
        batch.require();
        batch.require();
        batch.decidedTimes(2);
        verify(batch.copies(), times(2)).recordIdentity(batch.contributor().copy());
    }

    /** Row 2: in a batch, a permitted contributor is decided by the first source and at settlement only. */
    @Test
    void shouldDecideAPermittedContributorOnceAndAtSettlementInABatch() {
        var batch = Batch.of(AiChoice.CLOUD);
        batch.inBatch(() -> {
            batch.require();
            batch.require();
            batch.require();
        });
        batch.decidedTimes(2);
        verify(batch.copies(), times(3)).recordIdentity(batch.contributor().copy());
    }

    /** Row 3: a withheld contributor keeps no verdict: every source that names them decides and fails. */
    @Test
    void shouldKeepNoVerdictForAWithheldContributorInABatch() {
        var batch = Batch.of(AiChoice.NO_AI);
        batch.inBatch(() -> {
            assertWithheld(batch::require);
            assertWithheld(batch::require);
        });
        batch.decidedTimes(2);
        verify(batch.copies(), never()).recordIdentity(any());
    }

    /** Row 3, conflict: an identity that resolves to two accounts withholds with its cause, each time, as on main. */
    @Test
    void shouldWithholdAConflictingIdentityWithItsCauseEachTimeInABatch() {
        var batch = Batch.of(AiChoice.CLOUD);
        var conflict = new ResponseStatusException(HttpStatus.CONFLICT);
        when(batch.identities().accountFor(batch.contributor().identity())).thenThrow(conflict);
        batch.inBatch(() -> {
            assertThatThrownBy(batch::require)
                    .isInstanceOf(ItemCaptureException.class)
                    .hasCause(conflict);
            assertThatThrownBy(batch::require)
                    .isInstanceOf(ItemCaptureException.class)
                    .hasCause(conflict);
        });
        batch.decidedTimes(2);
    }

    /** Row 4: a contributor whom only a later source names is decided by that source, then at settlement. */
    @Test
    void shouldDecideAContributorWhenTheFirstSourceThatNamesThemIsChecked() {
        var batch = Batch.of(AiChoice.CLOUD);
        var other = new ScmCaptureConsent.Contributor(
                new PersonIdentity(1L, "22", null), new PersonCopyIdentity("GITHUB", "https://github.com", "22", null));
        when(batch.identities().accountFor(other.identity())).thenReturn(Optional.empty());
        batch.inBatch(() -> {
            batch.require();
            batch.consent().requireContributors(List.of(batch.contributor(), other));
        });
        batch.decidedTimes(2);
        verify(batch.identities(), times(2)).accountFor(other.identity());
        verify(batch.copies(), times(1)).recordIdentity(other.copy());
    }

    /** Row 5: a check that fails with another error keeps no verdict, so the next source decides again. */
    @Test
    void shouldKeepNoVerdictWhenACheckFailsWithAnotherErrorInABatch() {
        var batch = Batch.of(AiChoice.CLOUD);
        var unavailable = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        when(batch.identities().accountFor(batch.contributor().identity()))
                .thenThrow(unavailable)
                .thenReturn(Optional.of(101L));
        batch.inBatch(() -> {
            assertThatThrownBy(batch::require).isSameAs(unavailable);
            batch.require();
        });
        batch.decidedTimes(3);
        verify(batch.copies(), times(1)).recordIdentity(batch.contributor().copy());
    }

    /** Row 6: the verdict holds no copy; each source records the copy of the identity's first occurrence in it. */
    @Test
    void shouldRecordEachSourcesOwnCopyOfAKeptContributorInABatch() {
        var batch = Batch.of(AiChoice.CLOUD);
        var retained = new ScmCaptureConsent.Contributor(
                batch.contributor().identity(), Batch.copy("https://github.example.test"));
        batch.inBatch(() -> {
            batch.require();
            batch.consent().requireContributors(List.of(retained));
        });
        batch.decidedTimes(2);
        verify(batch.copies(), times(1)).recordIdentity(batch.contributor().copy());
        verify(batch.copies(), times(1)).recordIdentity(retained.copy());
    }

    /** Row 7: a nested batch joins the open one and neither settles nor closes it; the outer batch drops the verdicts. */
    @Test
    void shouldJoinANestedBatchAndDecideAgainAfterTheOuterBatchCloses() {
        var batch = Batch.of(AiChoice.CLOUD);
        batch.inBatch(() -> {
            batch.require();
            var nested = ScmCaptureConsent.openBatch(true);
            assertThat(nested).isFalse();
            ScmCaptureConsent.settleBatch(nested);
            ScmCaptureConsent.closeBatch(nested);
            batch.require();
        });
        batch.decidedTimes(2);
        batch.require();
        batch.decidedTimes(3);
        assertThat(ScmCaptureConsent.openBatch(false)).isFalse();
    }

    /** Row 8: a withdrawal that commits while a batch runs fails it at settlement, as a later source's check would. */
    @Test
    void shouldFailTheBatchWhenConsentIsWithdrawnWhileItRuns() {
        var batch = Batch.of(AiChoice.CLOUD);
        assertWithheld(() -> batch.inBatch(() -> {
            batch.require();
            when(batch.accounts().find(101)).thenReturn(Optional.of(Batch.account(AiChoice.NO_AI)));
            batch.require();
        }));
        batch.decidedTimes(2);
    }

    @Test
    void shouldWithholdWhenALaterSourceSeesANewlyLinkedAccountBeforeItsChoiceChanges() {
        var batch = Batch.of(AiChoice.CLOUD);
        var linked = new AtomicBoolean();
        var choice = new AtomicReference<>(AiChoice.NO_AI);
        when(batch.identities().accountFor(batch.contributor().identity()))
                .thenAnswer(invocation -> linked.get() ? Optional.of(101L) : Optional.empty());
        when(batch.accounts().find(101L)).thenAnswer(invocation -> Optional.of(Batch.account(choice.get())));

        // Model the committed states visible to each read of this SCM identity.
        Runnable sources = () -> {
            batch.require();
            linked.set(true);
            batch.require();
            choice.set(AiChoice.CLOUD);
        };

        assertWithheld(sources);
        linked.set(false);
        assertWithheld(() -> batch.inBatch(sources));
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
