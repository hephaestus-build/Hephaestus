package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory.AccountView;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRetireReason;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Refused;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.RetireAdvice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFeedbackProducers;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviseAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Table A rows 1 to 3: nothing is read or locked for a write that is not enabled or has no eligible owner. */
class PracticeAdviceWriterAdmissionTest extends BaseUnitTest {
    private static final long WS = 7;
    private static final long OWNER = 11;
    private static final UUID REVIEW = UUID.randomUUID();

    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final PersonDataWriteFence fence = mock(PersonDataWriteFence.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final AdviceRows rows = mock(AdviceRows.class);
    private final ReviewFeedbackProducers mentor = (ws, review) -> Optional.of(FeedbackProducer.MENTOR);
    private final AdviceErasureListener listener = (ws, revisions, cause) -> {};

    @Test
    void shouldRefuseAWorkspaceThatIsNotListed() {
        var writer = writer(Set.of(), false, List.of(mentor), List.of(listener));

        assertRefused(writer, AdviceRefusal.NOT_ENABLED);
        verifyNoInteractions(fence, accounts, rows);
    }

    @Test
    void shouldRefuseAListedWorkspaceWithoutAnErasureListenerWhenTheProofPropertyIsOff() {
        var writer = writer(Set.of(WS), false, List.of(mentor), List.of());

        assertRefused(writer, AdviceRefusal.NOT_ENABLED);
        verifyNoInteractions(fence, accounts, rows);
    }

    @Test
    void shouldAdmitAListedWorkspaceWithoutAnErasureListenerOnlyWhenTheProofPropertyIsOn() {
        owner(Account.Status.ACTIVE, true, true);
        var writer = writer(Set.of(WS), true, List.of((ws, review) -> Optional.of(FeedbackProducer.LEGACY)), List.of());

        assertRefused(writer, AdviceRefusal.NOT_MENTOR_REVIEW);
        verify(fence, atLeastOnce()).holdForAccountWrite(OWNER);
    }

    @Test
    void shouldRefuseWhileNoProducerIsRecorded() {
        var writer = writer(Set.of(WS), false, List.of(), List.of(listener));

        assertRefused(writer, AdviceRefusal.NOT_ENABLED);
    }

    @Test
    void shouldGiveAnUnclaimedAccountNoAdvice() {
        owner(Account.Status.UNCLAIMED, true, true);

        assertRefused(enabled(), AdviceRefusal.OWNER_UNAVAILABLE);
        verifyNoInteractions(rows);
    }

    @Test
    void shouldRefuseAnErasedOwner() {
        owner(Account.Status.DELETING, true, true);

        assertRefused(enabled(), AdviceRefusal.OWNER_UNAVAILABLE);
    }

    @Test
    void shouldRefuseAnOwnerWhoLeftTheWorkspace() {
        owner(Account.Status.ACTIVE, false, true);

        assertRefused(enabled(), AdviceRefusal.OWNER_UNAVAILABLE);
    }

    @Test
    void shouldRefuseAnOwnerWhoseProcessingIsSuppressed() {
        owner(Account.Status.ACTIVE, true, false);

        assertRefused(enabled(), AdviceRefusal.OWNER_UNAVAILABLE);
        verify(accounts, never()).find(OWNER);
    }

    @Test
    void shouldRefuseAReviewThatTheMentorDidNotProduce() {
        owner(Account.Status.ACTIVE, true, true);
        for (var producer : List.<Optional<FeedbackProducer>>of(
                Optional.empty(), Optional.of(FeedbackProducer.LEGACY), Optional.of(FeedbackProducer.WORK_PLACE))) {
            var writer = writer(Set.of(WS), false, List.of((ws, review) -> producer), List.of(listener));

            assertRefused(writer, AdviceRefusal.NOT_MENTOR_REVIEW);
        }
        verifyNoInteractions(rows);
    }

    private void owner(Account.Status status, boolean member, boolean fenceAdmits) {
        when(fence.holdForAccountWrite(OWNER)).thenReturn(fenceAdmits);
        when(accounts.find(OWNER)).thenReturn(Optional.of(new AccountView(OWNER, status, "Owner", null, null, true)));
        when(memberships.isActiveVisibleMember(WS, OWNER)).thenReturn(member);
    }

    private PracticeAdviceWriter enabled() {
        return writer(Set.of(WS), false, List.of(mentor), List.of(listener));
    }

    private PracticeAdviceWriter writer(
            Set<Long> workspaces,
            boolean allowWithoutListener,
            List<ReviewFeedbackProducers> producers,
            List<AdviceErasureListener> listeners) {
        return new PracticeAdviceWriter(
                new PracticeAdviceProperties(workspaces, allowWithoutListener),
                producers,
                listeners,
                accounts,
                memberships,
                fence,
                reviews,
                mock(FeedbackGate.class),
                mock(FeedbackBasisRepository.class),
                rows);
    }

    private static void assertRefused(PracticeAdviceWriter writer, AdviceRefusal reason) {
        AdviceWrite revise = writer.revise(new ReviseAdvice(
                WS,
                OWNER,
                3,
                REVIEW,
                null,
                "Add a test.",
                List.of(new EvidenceRef(UUID.randomUUID(), null)),
                List.of()));
        AdviceWrite retire = writer.retire(new RetireAdvice(WS, OWNER, 3, REVIEW, 1, AdviceRetireReason.RESOLVED));
        assertThat(List.of(revise, retire)).containsOnly(new Refused(reason, null));
    }
}
