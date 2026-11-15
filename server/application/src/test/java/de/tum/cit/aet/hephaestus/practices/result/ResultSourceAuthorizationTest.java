package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ResultSourceAuthorizationTest extends BaseUnitTest {
    final ProductItemQuery items = mock(ProductItemQuery.class);
    final PersonProcessingSuppression suppression = mock(PersonProcessingSuppression.class);
    final PersonIdentityResolver people = mock(PersonIdentityResolver.class);
    final AccountDirectory accounts = mock(AccountDirectory.class);
    final ItemSourceAccess source = mock(ItemSourceAccess.class);
    final ClasspathSourceCatalogRegistry catalog = mock(ClasspathSourceCatalogRegistry.class);
    final ProblemRepository problems = mock(ProblemRepository.class);
    final ProductItemRepository itemRows = mock(ProductItemRepository.class);
    final JsonMapper json = new JsonMapper();
    final SourceUsePurpose purpose = SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW;
    final ItemRef item = new ItemRef(1, 2, new ItemKind("scm.pull_request"), 3L, null, null);
    final UUID job = UUID.randomUUID();
    final Result result =
            Result.builder().id(UUID.randomUUID()).workspaceId(1).itemId(2).build();
    final ResultRepository results = mock(ResultRepository.class);
    final ReviewItemRepository assignments = mock(ReviewItemRepository.class);
    final HistoryCustodyQuery custody = mock(HistoryCustodyQuery.class);
    final ResultSourceAuthorization authorization = new ResultSourceAuthorization(
            items,
            assignments,
            custody,
            results,
            json,
            catalog,
            suppression,
            people,
            accounts,
            problems,
            itemRows,
            List.of(source));

    @BeforeEach
    void availableSource() {
        Mockito.lenient().when(items.find(1, 2)).thenReturn(Optional.of(item));
        Mockito.lenient().when(source.kinds()).thenReturn(Set.of(item.kind()));
        Mockito.lenient().when(source.permits(item, purpose)).thenReturn(true);
    }

    HistoryCustody receipt(List<PersonCopyIdentity> copies) {
        return new HistoryCustody(true, false, copies, List.of(), List.of(job));
    }

    @Test
    void developerlessProofUsesItsCapturedCustodyAndCurrentSourceAuthority() {
        assertThat(authorization.authorized(result, receipt(List.of()), purpose))
                .isTrue();
        when(source.permits(item, purpose)).thenReturn(false);
        assertThat(authorization.authorized(result, receipt(List.of()), purpose))
                .isFalse();
    }

    @Test
    void unknownCustodyAndSuppressedProducerAreUnavailable() {
        assertThat(authorization.authorized(
                        result, new HistoryCustody(false, false, List.of(), List.of(), List.of()), purpose))
                .isFalse();
        when(suppression.isReviewJobSuppressed(job)).thenReturn(true);
        assertThat(authorization.authorized(result, receipt(List.of()), purpose))
                .isFalse();
    }

    @Test
    void exactCapturedNativeIdentityMustStillPermitProcessing() {
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "native-7", null);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "native-7", null))).thenReturn(Optional.of(7L));
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.UNCLAIMED, "Person", AiChoice.CLOUD, null, true)));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isTrue();
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.UNCLAIMED, "Person", AiChoice.NO_AI, null, true)));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
        when(suppression.isSuppressed(8, "native-7", null)).thenReturn(true);
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
    }

    @Test
    void shouldPermitCopiedContributorsWithoutAnyLinkWhenTheyAreNotSuppressed() {
        var person = new PersonCopyIdentity("GITHUB", "https://github.com", "274126659", null);
        var bot = new PersonCopyIdentity("GITHUB", "https://github.com", "235263273", null);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "274126659", null))).thenReturn(Optional.empty());
        when(people.accountFor(new PersonIdentity(8, "235263273", null))).thenReturn(Optional.empty());
        assertThat(authorization.authorized(result, receipt(List.of(person, bot)), purpose))
                .isTrue();
        when(suppression.isSuppressed(8, "235263273", null)).thenReturn(true);
        assertThat(authorization.authorized(result, receipt(List.of(person, bot)), purpose))
                .isFalse();
    }

    @Test
    void shouldApplyAccountControlsThroughDisabledLinksAndRefuseAMissingAccountRow() {
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "native-7", null);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "native-7", null))).thenReturn(Optional.of(7L));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.DELETING, "Person", AiChoice.CLOUD, null, true)));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.CLOUD, null, true)));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isTrue();
        when(suppression.isAccountSuppressed(7)).thenReturn(true);
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
    }

    @Test
    void shouldRefuseCopiedIdentitiesThatTheExactResolverCannotAttribute() {
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "native-7", null);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "native-7", null)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThat(authorization.authorized(result, receipt(List.of(copy)), purpose))
                .isFalse();
        var foreign = new PersonCopyIdentity("GITHUB", "https://github.example", "native-7", null);
        assertThat(authorization.authorized(result, receipt(List.of(foreign)), purpose))
                .isFalse();
    }

    @Test
    void storedSourceKindUsesCurrentGovernanceRatherThanAnOldDefinition() {
        var evidence = json.createObjectNode();
        evidence.putArray("citations").addObject().put("sourceKind", "scm.pull-request.diff");
        var proof = resultBuilder(result).evidence(evidence).build();
        var kind = new SourceKind("scm.pull-request.diff");
        when(catalog.isSourceUsePermitted(kind, purpose)).thenReturn(false);
        assertThat(authorization.authorized(proof, receipt(List.of()), purpose)).isFalse();
        when(catalog.isSourceUsePermitted(kind, purpose)).thenReturn(true);
        assertThat(authorization.authorized(proof, receipt(List.of()), purpose)).isTrue();
    }

    @Test
    void shouldKeepMigratedHistoryBehindSourceConsentAndErasureChecks() {
        var history = resultBuilder(result)
                .evidence(json.readTree("{\"citations\":[{\"sourceKind\":\"hephaestus.observation-history\"}]}"))
                .evidenceVersion((short) 0)
                .developerId(7L)
                .reviewId(job)
                .build();
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.CLOUD, null, true)));
        when(source.permits(item, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)).thenReturn(true);
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        assertThat(authorization.authorized(history, receipt(List.of()), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        when(assignments.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(1, job, history.getPracticeId(), 2))
                .thenReturn(Optional.of(ReviewItem.builder()
                        .resultId(history.getId())
                        .jobId(job)
                        .build()));
        when(custody.find(1, List.of(job))).thenReturn(receipt(List.of()));
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        assertThat(authorization.permits(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        when(source.permits(item, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)).thenReturn(false);
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        when(source.permits(item, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)).thenReturn(true);
        when(suppression.isAccountSuppressed(7)).thenReturn(true);
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        when(suppression.isAccountSuppressed(7)).thenReturn(false);
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.NO_AI, null, true)));
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.CLOUD, null, true)));
        when(suppression.isReviewJobSuppressed(job)).thenReturn(true);
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
    }

    @Test
    void shouldReadEachRepositoryOncePerReadAndRefuseARemovedOne() {
        var history =
                resultBuilder(result).evidenceVersion((short) 0).reviewId(job).build();
        var purpose = SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW;
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));
        when(source.permits(item, purpose)).thenReturn(true);
        when(assignments.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(1, job, history.getPracticeId(), 2))
                .thenReturn(Optional.of(ReviewItem.builder()
                        .resultId(history.getId())
                        .jobId(job)
                        .build()));
        when(custody.find(1, List.of(job)))
                .thenReturn(new HistoryCustody(false, false, List.of(), List.of(9L), List.of(job)));
        when(itemRows.activeRepositoryIds(1, 9)).thenReturn(List.of(2L));
        // Two checks of the same result in one read: the second reuses the first one's repository read.
        assertThat(authorization.asOneRead(
                        1,
                        () -> List.of(
                                authorization.permitsHistory(1, history.getId(), purpose),
                                authorization.permitsHistory(1, history.getId(), purpose))))
                .containsExactly(true, true);
        verify(itemRows, times(1)).activeRepositoryIds(1, 9);

        // A new read asks again, and a removed repository refuses the result.
        when(itemRows.activeRepositoryIds(1, 9)).thenReturn(List.of());
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();
        verify(itemRows, times(2)).activeRepositoryIds(1, 9);
    }

    @Test
    void shouldRetainPrivacyChecksWhenMigratedCustodyIsIncomplete() {
        var history =
                resultBuilder(result).evidenceVersion((short) 0).reviewId(job).build();
        var purpose = SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW;
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));
        when(source.permits(item, purpose)).thenReturn(true);
        when(assignments.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(1, job, history.getPracticeId(), 2))
                .thenReturn(Optional.of(ReviewItem.builder()
                        .resultId(history.getId())
                        .jobId(job)
                        .build()));
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "native-7", null);
        when(custody.find(1, List.of(job)))
                .thenReturn(new HistoryCustody(false, false, List.of(copy), List.of(), List.of(job)));
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "native-7", null))).thenReturn(Optional.of(7L));
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.CLOUD, null, true)));
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isTrue();
        assertThat(authorization.permits(1, history.getId(), purpose)).isFalse();
        when(suppression.isSuppressed(8, "native-7", null)).thenReturn(true);
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();

        when(custody.find(1, List.of(job)))
                .thenReturn(new HistoryCustody(false, false, List.of(), List.of(9L), List.of(job)));
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();
        when(itemRows.activeRepositoryIds(1, 9)).thenReturn(List.of(2L));
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isTrue();

        var copiedJob = UUID.randomUUID();
        when(custody.find(1, List.of(job)))
                .thenReturn(new HistoryCustody(false, false, List.of(), List.of(), List.of(copiedJob)));
        when(suppression.isReviewJobSuppressed(copiedJob)).thenReturn(true);
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();
        when(custody.find(1, List.of(job)))
                .thenReturn(new HistoryCustody(false, true, List.of(), List.of(), List.of()));
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();
    }

    @Test
    void shouldRefuseConflictingRetainedCustodyInsteadOfTreatingItAsAbsent() {
        var history =
                resultBuilder(result).evidenceVersion((short) 0).reviewId(job).build();
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));
        when(source.permits(item, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)).thenReturn(true);
        var first = json.createObjectNode().set("custody", json.valueToTree(receipt(List.of())));
        var second = json.createObjectNode()
                .set("custody", json.valueToTree(new HistoryCustody(false, false, List.of(), List.of(), List.of(job))));
        when(assignments.findByWorkspaceIdAndResultIdOrderByReviewIdAscPracticeIdAscItemIdAsc(1, history.getId()))
                .thenReturn(List.of(
                        ReviewItem.builder().detail(first).build(),
                        ReviewItem.builder().detail(second).build()));
        assertThat(authorization.permitsHistory(1, history.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
    }

    @Test
    void shouldCountAStandingOfAMemberWhoChoseNoAiWhileEveryProcessingCheckStillRefusesIt() {
        var standingPurpose = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
        when(source.permits(item, standingPurpose)).thenReturn(true);
        when(source.permits(item, standingPurpose, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .thenReturn(true);
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "native-7", null);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        when(people.accountFor(new PersonIdentity(8, "native-7", null))).thenReturn(Optional.of(7L));
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.NO_AI, null, true)));
        var current = resultBuilder(result).reviewId(job).developerId(7L).build();
        var history = resultBuilder(result)
                .id(UUID.randomUUID())
                .evidenceVersion((short) 0)
                .reviewId(job)
                .developerId(7L)
                .build();
        var producer = ReviewItem.builder()
                .workspaceId(1)
                .reviewId(job)
                .practiceId(current.getPracticeId())
                .itemId(2)
                .resultId(current.getId())
                .jobId(job)
                .build();
        // A single check reads its own producer; a read of many reads its reviews' assignments together.
        when(assignments.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(1, job, current.getPracticeId(), 2))
                .thenReturn(Optional.of(producer));
        when(assignments.findByWorkspaceIdAndReviewIdIn(1, List.of(job))).thenReturn(List.of(producer));
        when(custody.find(1, List.of(job))).thenReturn(receipt(List.of(copy)));
        when(results.findByIdAndWorkspaceId(current.getId(), 1)).thenReturn(Optional.of(current));
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));

        assertThat(authorization.permitsHumanStanding(1, List.of(current, history)))
                .containsExactlyInAnyOrder(current.getId(), history.getId());
        // Every AI, history and delivery reader keeps the AI choice.
        assertThat(authorization.permits(1, current.getId(), standingPurpose)).isFalse();
        assertThat(authorization.permitsHistory(1, history.getId(), standingPurpose))
                .isFalse();
        assertThat(authorization.permitsHumanStanding(
                        2,
                        List.of(current, resultBuilder(current).workspaceId(2).build())))
                .isEmpty();

        when(suppression.isAccountSuppressed(7)).thenReturn(true);
        assertThat(authorization.permitsHumanStanding(1, List.of(current, history)))
                .isEmpty();
        when(suppression.isAccountSuppressed(7)).thenReturn(false);
        when(source.permits(item, standingPurpose, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .thenReturn(false);
        assertThat(authorization.permitsHumanStanding(1, List.of(current, history)))
                .isEmpty();
    }

    @Test
    void shouldAskTheSourceForTheHumanReadOnlyWhenCountingAStanding() {
        var purpose = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
        var history = resultBuilder(result)
                .evidenceVersion((short) 0)
                .reviewId(job)
                .developerId(7L)
                .build();
        when(accounts.find(7))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        7, Account.Status.ACTIVE, "Person", AiChoice.CLOUD, null, true)));
        when(results.findByIdAndWorkspaceId(history.getId(), 1)).thenReturn(Optional.of(history));
        // The source alone decides: it refuses every AI read of the item and permits only the human standing.
        when(source.permits(item, purpose)).thenReturn(false);
        when(source.permits(item, purpose, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .thenReturn(true);

        assertThat(authorization.permitsHumanStanding(1, List.of(history))).containsExactly(history.getId());
        assertThat(authorization.permitsHistory(1, history.getId(), purpose)).isFalse();
        verify(source, never()).permits(item, purpose, SourceReadAudience.PROCESSING);
    }

    @Test
    void shouldNameOnlyTheJobOfTheOwnAssignmentThatRecordedExactlyThisResultWhenReadingProducers() {
        var produced = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var copied = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var legacy = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .evidenceVersion((short) 0)
                .build();
        var jobless = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .build();
        var foreign = resultBuilder(result)
                .id(UUID.randomUUID())
                .reviewId(UUID.randomUUID())
                .workspaceId(2)
                .build();
        var dependency = UUID.randomUUID();
        when(assignments.findByWorkspaceIdAndReviewIdIn(
                        1,
                        List.of(
                                produced.getReviewId(),
                                copied.getReviewId(),
                                legacy.getReviewId(),
                                jobless.getReviewId())))
                .thenReturn(List.of(
                        assignment(produced, produced.getPracticeId(), produced.getId(), job),
                        // The same review's assignment of another practice records this result with another job.
                        assignment(produced, produced.getPracticeId() + 1, produced.getId(), dependency),
                        // The own assignment records the result that this review copied, not this one.
                        assignment(copied, copied.getPracticeId(), UUID.randomUUID(), dependency),
                        ReviewItem.builder()
                                .workspaceId(1)
                                .reviewId(legacy.getReviewId())
                                .practiceId(legacy.getPracticeId())
                                .itemId(2)
                                .state(ReviewItemState.WITHHELD)
                                .reason(ReviewItem.LEGACY_AMBIGUOUS_RESULTS)
                                .jobId(dependency)
                                .build(),
                        assignment(jobless, jobless.getPracticeId(), jobless.getId(), null)));

        assertThat(authorization.producingJobs(1, List.of(produced, copied, legacy, jobless, foreign)))
                .isEqualTo(Map.of(produced.getId(), job));
        verify(assignments).findByWorkspaceIdAndReviewIdIn(eq(1L), any());
        verifyNoInteractions(custody);
    }

    private static ReviewItem assignment(
            Result result, long practiceId, @Nullable UUID recordedResult, @Nullable UUID producingJob) {
        return ReviewItem.builder()
                .workspaceId(result.getWorkspaceId())
                .reviewId(result.getReviewId())
                .practiceId(practiceId)
                .itemId(result.getItemId())
                .state(ReviewItemState.JUDGED)
                .resultId(recordedResult)
                .jobId(producingJob)
                .build();
    }

    @Test
    void shouldReadRetainedCustodyWrittenBeforeRefusalWasRecordedSeparately() {
        var retained = json.readValue(
                "{\"complete\":false,\"copiedIdentities\":[],\"repositoryIds\":[],\"sourceJobIds\":[]}",
                HistoryCustody.class);
        assertThat(retained).isEqualTo(new HistoryCustody(false, false, List.of(), List.of(), List.of()));
    }
}
