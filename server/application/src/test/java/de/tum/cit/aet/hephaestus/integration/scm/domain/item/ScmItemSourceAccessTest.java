package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.scm.ScmPersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ScmItemSourceAccessTest extends BaseUnitTest {
    private static final ItemRef ROOT = new ItemRef(1, 2, ScmItemEvents.REPOSITORY, 3L, null, null);
    private static final ItemRef FILE = new ItemRef(1, 4, ScmItemEvents.FILE, null, ROOT.itemId(), "src/a.ts");
    private static final SourceUsePurpose PURPOSE = SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW;

    @Test
    void shouldAuthorizeAnIndexedFileThroughItsCurrentRepositoryWithoutCapturingIt() {
        var fixture = fixture();
        assertThat(fixture.service().permits(FILE, PURPOSE)).isTrue();
    }

    @Test
    void shouldRejectRemovedOrForeignCanonicalItems() {
        var fixture = fixture();
        when(fixture.items().require(FILE))
                .thenThrow(new ItemCaptureException(ItemCaptureCode.ITEM_REMOVED, "Removed"));
        assertThat(fixture.service().permits(FILE, PURPOSE)).isFalse();
        var foreign = new ItemRef(9, FILE.itemId(), FILE.kind(), FILE.sourceId(), FILE.parentId(), FILE.path());
        when(fixture.items().require(foreign))
                .thenThrow(new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Foreign"));
        assertThat(fixture.service().permits(foreign, PURPOSE)).isFalse();
    }

    @Test
    void shouldRejectARepositoryWhoseCurrentConnectionOrMonitorIdentityChanged() {
        var fixture = fixture();
        doThrow(new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Changed origin"))
                .when(fixture.access())
                .authorize(1, fixture.repository());
        assertThat(fixture.service().permits(FILE, PURPOSE)).isFalse();
    }

    @Test
    void shouldRecheckEveryCurrentContributorWithoutCreatingAccountsOrCopyReceipts() {
        var fixture = fixture();
        var ref = new ItemRef(1, 5, ScmItemEvents.ISSUE, 6L, ROOT.itemId(), null);
        when(fixture.items().parent(ref, ScmItemEvents.REPOSITORY)).thenReturn(ROOT);
        var issue = stored(ROOT.sourceId(), false);
        when(fixture.items().work(ScmItemEvents.ISSUE, 6L)).thenReturn(Optional.of(issue));
        when(fixture.contributors().identitiesForSource(1, "scm.issue", 6))
                .thenReturn(List.of(new PersonIdentity(7, "author", null), new PersonIdentity(7, "commenter", null)));
        when(fixture.suppression().isSuppressed(7, "author", null)).thenReturn(false);
        when(fixture.suppression().isSuppressed(7, "commenter", null)).thenReturn(false);
        when(fixture.identities().accountFor(new PersonIdentity(7L, "author", null)))
                .thenReturn(Optional.of(8L));
        when(fixture.identities().accountFor(new PersonIdentity(7L, "commenter", null)))
                .thenReturn(Optional.of(9L));
        when(fixture.accounts().find(8L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        8, Account.Status.ACTIVE, "Author", AiChoice.CLOUD, null, true)));
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.ACTIVE, "Commenter", AiChoice.NO_AI, null, true)));
        assertThat(fixture.service().permits(ref, PURPOSE)).isFalse();
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.ACTIVE, "Commenter", AiChoice.IN_HOUSE_ONLY, null, true)));
        assertThat(fixture.service().permits(ref, PURPOSE)).isTrue();
        when(fixture.accounts().find(9L)).thenReturn(Optional.empty());
        assertThat(fixture.service().permits(ref, PURPOSE)).isFalse();
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.ACTIVE, "Commenter", AiChoice.IN_HOUSE_ONLY, null, true)));
        assertThat(fixture.service().permits(ref, PURPOSE)).isTrue();
        when(fixture.suppression().isSuppressed(7, "commenter", null)).thenReturn(true);
        assertThat(fixture.service().permits(ref, PURPOSE)).isFalse();
        when(fixture.suppression().isSuppressed(7, "commenter", null)).thenReturn(false);
        when(fixture.identities().accountFor(new PersonIdentity(7, "commenter", null)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThat(fixture.service().permits(ref, PURPOSE)).isFalse();
    }

    @Test
    void shouldCountAHumanStandingOverANoAiContributorWhileEveryAiReadStaysRefused() {
        var fixture = fixture();
        var ref = issueWithNoAiCommenter(fixture);
        for (var purpose : SourceUsePurpose.values()) {
            assertThat(fixture.service().permits(ref, purpose)).isFalse();
            assertThat(fixture.service().permits(ref, purpose, SourceReadAudience.PROCESSING))
                    .isFalse();
        }
        assertThat(fixture.service().permits(ref, PURPOSE, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .isTrue();
    }

    @Test
    void shouldKeepEveryOtherSourceControlWhenTheHumanStandingIsRead() {
        var fixture = fixture();
        var ref = issueWithNoAiCommenter(fixture);
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        when(fixture.suppression().isSuppressed(7, "author", null)).thenReturn(false);
        when(fixture.suppression().isAccountSuppressed(8L)).thenReturn(false);
        when(fixture.suppression().isSuppressed(7, "commenter", null)).thenReturn(true);
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isFalse();
        when(fixture.suppression().isSuppressed(7, "commenter", null)).thenReturn(false);
        when(fixture.suppression().isAccountSuppressed(9L)).thenReturn(true);
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isFalse();
        when(fixture.suppression().isAccountSuppressed(9L)).thenReturn(false);
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.DELETING, "Commenter", AiChoice.NO_AI, null, true)));
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isFalse();
        when(fixture.accounts().find(9L)).thenReturn(Optional.empty());
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isFalse();
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.ACTIVE, "Commenter", AiChoice.NO_AI, null, true)));
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isTrue();
        doThrow(new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Unmonitored"))
                .when(fixture.access())
                .authorize(1, fixture.repository());
        assertThat(fixture.service().permits(ref, PURPOSE, human)).isFalse();
    }

    @Test
    void shouldCarryTheHumanReadThroughAReviewToItsPullRequest() {
        var fixture = fixture();
        var pullRequestRef = new ItemRef(1, 10, ScmItemEvents.PULL_REQUEST, 11L, ROOT.itemId(), null);
        var reviewRef = new ItemRef(1, 12, ScmItemEvents.PULL_REQUEST_REVIEW, 13L, pullRequestRef.itemId(), null);
        when(fixture.items().parent(pullRequestRef, ScmItemEvents.REPOSITORY)).thenReturn(ROOT);
        when(fixture.items().parent(reviewRef, ScmItemEvents.PULL_REQUEST)).thenReturn(pullRequestRef);
        var pullRequest = new PullRequest();
        pullRequest.setId(11L);
        var stored = stored(ROOT.sourceId(), false);
        when(fixture.items().work(ScmItemEvents.PULL_REQUEST, 11L)).thenReturn(Optional.of(stored));
        var review = new PullRequestReview();
        review.setPullRequest(pullRequest);
        when(fixture.reviews().findById(13L)).thenReturn(Optional.of(review));
        // Only the pull request's author chose No AI; the review itself has no other contributor.
        when(fixture.contributors().identitiesForSource(1, "scm.pull_request", 11))
                .thenReturn(List.of(new PersonIdentity(7, "author", null)));
        when(fixture.identities().accountFor(new PersonIdentity(7L, "author", null)))
                .thenReturn(Optional.of(8L));
        when(fixture.accounts().find(8L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        8, Account.Status.ACTIVE, "Author", AiChoice.NO_AI, null, true)));

        assertThat(fixture.service().permits(reviewRef, PURPOSE)).isFalse();
        assertThat(fixture.service().permits(reviewRef, PURPOSE, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .isTrue();
    }

    private ItemRef issueWithNoAiCommenter(Fixture fixture) {
        var ref = new ItemRef(1, 5, ScmItemEvents.ISSUE, 6L, ROOT.itemId(), null);
        when(fixture.items().parent(ref, ScmItemEvents.REPOSITORY)).thenReturn(ROOT);
        var issue = stored(ROOT.sourceId(), false);
        when(fixture.items().work(ScmItemEvents.ISSUE, 6L)).thenReturn(Optional.of(issue));
        when(fixture.contributors().identitiesForSource(1, "scm.issue", 6))
                .thenReturn(List.of(new PersonIdentity(7, "author", null), new PersonIdentity(7, "commenter", null)));
        when(fixture.identities().accountFor(new PersonIdentity(7L, "author", null)))
                .thenReturn(Optional.of(8L));
        when(fixture.identities().accountFor(new PersonIdentity(7L, "commenter", null)))
                .thenReturn(Optional.of(9L));
        when(fixture.accounts().find(8L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        8, Account.Status.ACTIVE, "Author", AiChoice.CLOUD, null, true)));
        when(fixture.accounts().find(9L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        9, Account.Status.ACTIVE, "Commenter", AiChoice.NO_AI, null, true)));
        return ref;
    }

    @Test
    void shouldRefuseWorkThatIsDeletedOfAnotherTypeOrInAnotherRepository() {
        var fixture = fixture();
        var ref = new ItemRef(1, 5, ScmItemEvents.PULL_REQUEST, 6L, ROOT.itemId(), null);
        when(fixture.items().parent(ref, ScmItemEvents.REPOSITORY)).thenReturn(ROOT);
        var deleted = stored(ROOT.sourceId(), true);
        var elsewhere = stored(4L, false);
        var unfiled = stored(null, false);
        when(fixture.items().work(ScmItemEvents.PULL_REQUEST, 6L))
                .thenReturn(Optional.of(deleted))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(elsewhere))
                .thenReturn(Optional.of(unfiled));
        for (int read = 0; read < 4; read++)
            assertThat(fixture.service().permits(ref, PURPOSE)).isFalse();
    }

    /** The stored relation facts of one issue or pull request; Mockito leaves every other fact empty. */
    private static ScmItemQueryRepository.WorkFacts stored(@Nullable Long repositoryId, boolean deleted) {
        var facts = mock(ScmItemQueryRepository.WorkFacts.class);
        lenient().when(facts.getRepositoryId()).thenReturn(repositoryId);
        lenient().when(facts.getDeleted()).thenReturn(deleted);
        return facts;
    }

    private Fixture fixture() {
        var items = mock(ScmItemAccess.class);
        var repositories = mock(RepositoryRepository.class);
        var reviews = mock(PullRequestReviewRepository.class);
        var access = mock(ScmRepositoryAccess.class);
        var contributors = mock(ScmPersonSourceIdentityContributor.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var identities = mock(PersonIdentityResolver.class);
        var accounts = mock(AccountDirectory.class);
        var repository = new Repository();
        repository.setId(3L);
        lenient().when(items.require(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(items.parent(FILE, ScmItemEvents.REPOSITORY)).thenReturn(ROOT);
        lenient().when(repositories.findById(3L)).thenReturn(Optional.of(repository));
        var service = new ScmItemSourceAccess(
                items,
                mock(ItemIndexService.class),
                repositories,
                reviews,
                access,
                contributors,
                suppression,
                identities,
                accounts);
        return new Fixture(
                service, items, reviews, access, contributors, suppression, identities, accounts, repository);
    }

    private record Fixture(
            ScmItemSourceAccess service,
            ScmItemAccess items,
            PullRequestReviewRepository reviews,
            ScmRepositoryAccess access,
            ScmPersonSourceIdentityContributor contributors,
            PersonProcessingSuppression suppression,
            PersonIdentityResolver identities,
            AccountDirectory accounts,
            Repository repository) {}
}
