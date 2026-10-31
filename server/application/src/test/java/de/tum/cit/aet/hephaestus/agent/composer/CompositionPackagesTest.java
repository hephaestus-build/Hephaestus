package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CompositionPackagesTest {
    private static final UUID REVIEW = UUID.randomUUID();
    private static final FeedbackPackage PROPOSAL = new FeedbackPackage(List.of(), List.of(), true, "history-1");
    private static final PreparedPackage RECEIPT = new PreparedPackage("digest", Instant.EPOCH, List.of());

    @Test
    void shouldRequireACheckedDraftAndPublishTheExactCheckedWordsOnlyOnce() {
        var checks = new AtomicInteger();
        var publications = new AtomicInteger();
        var drafts = new CompositionPackages.Drafts(REVIEW, proposal -> checks.incrementAndGet(), proposal -> {
            assertThat(proposal).isSameAs(PROPOSAL);
            publications.incrementAndGet();
            return RECEIPT;
        });
        assertThatThrownBy(() -> drafts.publish("invented")).isInstanceOf(IllegalArgumentException.class);
        assertThat(publications).hasValue(0);
        String id = drafts.check(PROPOSAL);
        assertThat(id).hasSize(64).isEqualTo(PROPOSAL.draftId(REVIEW));
        assertThat(checks).hasValue(1);
        assertThat(publications).hasValue(0);
        assertThat(drafts.publish(id)).isEqualTo(RECEIPT);
        assertThat(drafts.publish(id)).isEqualTo(RECEIPT);
        assertThat(publications).hasValue(1);
    }

    @Test
    void shouldBindTheDraftIdToItsReviewAndExactHistoryAndPayload() {
        assertThat(PROPOSAL.draftId(REVIEW)).isNotEqualTo(PROPOSAL.draftId(UUID.randomUUID()));
        assertThat(PROPOSAL.draftId(REVIEW))
                .isNotEqualTo(new FeedbackPackage(List.of(), List.of(), true, "history-2").draftId(REVIEW));
        assertThat(PROPOSAL.draftId(REVIEW))
                .isNotEqualTo(
                        new FeedbackPackage(List.of(UUID.randomUUID()), List.of(), true, "history-1").draftId(REVIEW));
    }

    @Test
    void shouldNotAdmitADraftWhoseCheckWasRefused() {
        var drafts = new CompositionPackages.Drafts(
                REVIEW,
                proposal -> {
                    throw new IllegalArgumentException("Invalid basis");
                },
                proposal -> RECEIPT);
        assertThatThrownBy(() -> drafts.check(PROPOSAL)).hasMessage("Invalid basis");
        assertThatThrownBy(() -> drafts.publish(PROPOSAL.draftId(REVIEW))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRecheckTheAttemptFenceAtPublicationAfterASuccessfulCheck() {
        var owned = new AtomicBoolean(true);
        var drafts = new CompositionPackages.Drafts(
                REVIEW, proposal -> assertThat(owned).isTrue(), proposal -> {
                    if (!owned.get()) throw new ResultAdmissionService.StaleAttemptException();
                    return RECEIPT;
                });
        String id = drafts.check(PROPOSAL);
        owned.set(false);
        assertThatThrownBy(() -> drafts.publish(id)).isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
        owned.set(true);
        assertThat(drafts.publish(id)).isEqualTo(RECEIPT);
    }

    @Test
    void shouldRefuseAnotherPackageAfterTheDestinationAcceptedOne() {
        var drafts = new CompositionPackages.Drafts(REVIEW, proposal -> {}, proposal -> RECEIPT);
        String first = drafts.check(PROPOSAL);
        String second = drafts.check(new FeedbackPackage(List.of(), List.of(), true, "history-2"));
        drafts.publish(first);
        assertThatThrownBy(() -> drafts.publish(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already accepted");
    }
}
