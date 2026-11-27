package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.FeedbackProducerReason;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorReviewIntake;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewProducerPin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The selector's decision table (RV1-DESIGN.md S1, S2, S7, S8, S8c, S9 to S12) with the pin and the intake mocked. */
@Tag("unit")
class FeedbackProducerSelectionTest {
    private static final ItemRef PULL_REQUEST = new ItemRef(1, 2, new ItemKind("scm.pull_request"), 20L, null, null);
    private static final long AUTHOR = 42;

    private final FeedbackProducerProperties properties = new FeedbackProducerProperties();
    private final ReviewProducerPin pins = mock(ReviewProducerPin.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final MentorReviewIntake intake = mock(MentorReviewIntake.class);
    private final ReviewJob job = RuntimeReviewTestData.job();

    @BeforeEach
    void open() {
        properties.setAllowMentorBeforeAdviceErasure(true);
        properties.setMentorWorkspaceIds(Set.of(job.getWorkspaceId()));
        when(pins.pinned(anyLong(), any())).thenReturn(Optional.empty());
        when(intake.admit(any())).thenReturn(new MentorReviewIntake.Outcome.Admitted(UUID.randomUUID()));
    }

    @Test
    void shouldStayLegacyWhenTheGateIsClosedByDefault() {
        var closed = new FeedbackProducerProperties();
        closed.setMentorWorkspaceIds(Set.of(job.getWorkspaceId()));
        assigned(assignment(AUTHOR, AboutRole.AUTHOR));

        var choice = new FeedbackProducerSelection(closed, pins, assignments, intake)
                .choose(job, review(PULL_REQUEST, ReviewOrigin.REQUEST), "d");

        assertThat(choice).isEqualTo(choice(FeedbackProducer.LEGACY, FeedbackProducerReason.GATE_CLOSED));
        verify(pins).pin(job.getWorkspaceId(), job.getReviewId(), FeedbackProducer.LEGACY);
        verify(intake, never()).admit(any());
    }

    @Test
    void shouldStayLegacyForAWorkspaceThatDoesNotSelectTheMentor() {
        properties.setMentorWorkspaceIds(Set.of(job.getWorkspaceId() + 1));
        assigned(assignment(AUTHOR, AboutRole.AUTHOR));

        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .isEqualTo(choice(FeedbackProducer.LEGACY, FeedbackProducerReason.NOT_SELECTED));
    }

    @Test
    void shouldTakeThePinOfAnEarlierSealAndChooseNothing() {
        when(pins.pinned(job.getWorkspaceId(), job.getReviewId())).thenReturn(Optional.of(FeedbackProducer.MENTOR));
        properties.setAllowMentorBeforeAdviceErasure(false);

        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .isEqualTo(choice(FeedbackProducer.MENTOR, FeedbackProducerReason.INHERITED));
        verify(pins, never()).pin(anyLong(), any(), any());
        verify(intake, never()).admit(any());
    }

    @Test
    void shouldKeepEveryRouteWithoutACutoverOnLegacy() {
        assigned(assignment(AUTHOR, AboutRole.AUTHOR));
        assertThat(choose(review(RuntimeReviewTestData.ITEM, ReviewOrigin.REQUEST)))
                .as("an item that is no pull request")
                .isEqualTo(choice(FeedbackProducer.LEGACY, FeedbackProducerReason.UNSUPPORTED_ROUTE));
        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.BACKFILL)))
                .as("a backfill")
                .isEqualTo(choice(FeedbackProducer.LEGACY, FeedbackProducerReason.UNSUPPORTED_ROUTE));
        assigned(assignment(AUTHOR, AboutRole.AUTHOR), assignment(7L, AboutRole.REVIEWER));
        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .as("a practice about a reviewer keeps its private page")
                .isEqualTo(choice(FeedbackProducer.LEGACY, FeedbackProducerReason.UNSUPPORTED_ROUTE));
        verify(intake, never()).admit(any());
    }

    @Test
    void shouldGoToTheWorkPlaceWithoutOneLinkedAuthor() {
        assigned(assignment(null, AboutRole.AUTHOR), assignment(null, null));
        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .isEqualTo(choice(FeedbackProducer.WORK_PLACE, FeedbackProducerReason.NO_LINKED_AUTHOR));
        assigned(assignment(AUTHOR, AboutRole.AUTHOR), assignment(AUTHOR + 1, AboutRole.AUTHOR));
        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .isEqualTo(choice(FeedbackProducer.WORK_PLACE, FeedbackProducerReason.SEVERAL_AUTHORS));
        verify(intake, never()).admit(any());
    }

    @Test
    void shouldGoToTheMentorForTheOneLinkedAuthorThatTheIntakeAdmits() {
        assigned(assignment(AUTHOR, AboutRole.AUTHOR), assignment(null, null));

        assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                .isEqualTo(choice(FeedbackProducer.MENTOR, FeedbackProducerReason.OWNER_BOUND));
        verify(intake)
                .admit(argThat(review -> review.accountId() == AUTHOR
                        && review.reviewId().equals(job.getReviewId())
                        && review.jobId().equals(job.getId())
                        && review.admissionDigest().equals("d")));
        verify(pins).pin(job.getWorkspaceId(), job.getReviewId(), FeedbackProducer.MENTOR);
    }

    @Test
    void shouldGoToTheWorkPlaceWithTheReasonOfEachRefusal() {
        assigned(assignment(AUTHOR, AboutRole.AUTHOR));
        for (var refusal : MentorReviewIntake.Refusal.values()) {
            when(intake.admit(any())).thenReturn(new MentorReviewIntake.Outcome.Refused(refusal));
            assertThat(choose(review(PULL_REQUEST, ReviewOrigin.REQUEST)))
                    .as(refusal.name())
                    .isEqualTo(choice(FeedbackProducer.WORK_PLACE, FeedbackProducerReason.valueOf(refusal.name())));
        }
    }

    private FeedbackProducerSelection.Choice choose(ReviewSnapshot review) {
        return new FeedbackProducerSelection(properties, pins, assignments, intake).choose(job, review, "d");
    }

    private void assigned(ReviewAssignmentSnapshot... assigned) {
        when(assignments.load(job)).thenReturn(Arrays.asList(assigned));
    }

    private static FeedbackProducerSelection.Choice choice(FeedbackProducer producer, FeedbackProducerReason reason) {
        return new FeedbackProducerSelection.Choice(producer, reason);
    }

    private static ReviewAssignmentSnapshot assignment(@Nullable Long developer, @Nullable AboutRole about) {
        var base = RuntimeReviewTestData.assignment(1L, null);
        var practice = base.practice();
        return new ReviewAssignmentSnapshot(
                base.id(),
                PULL_REQUEST,
                base.version(),
                developer,
                base.practiceSlug(),
                new PinnedPractice(
                        practice.practiceId(),
                        practice.practiceVersionId(),
                        DefinitionTestFixtures.withAbout(practice.definition(), about),
                        practice.reviewDigest(),
                        practice.method()));
    }

    private static ReviewSnapshot review(ItemRef item, ReviewOrigin origin) {
        return new ReviewSnapshot(RuntimeReviewTestData.REVIEW, 1, item, ReviewKind.ITEM, null, origin, 1, List.of());
    }
}
