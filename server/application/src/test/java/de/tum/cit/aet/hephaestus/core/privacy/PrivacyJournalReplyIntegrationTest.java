package de.tum.cit.aet.hephaestus.core.privacy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** A reply that writes no synchronous fact never waits for the journal bucket, even while the bucket is unreachable. */
@Tag("integration")
class PrivacyJournalReplyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @MockitoBean
    private PrivacyJournalObjectStore bucket;

    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ReviewIntegrationData reviews;

    @Autowired
    private FeedbackRepository feedback;

    @Test
    void shouldNotWaitForAnUnreachableBucketOnASettingChange() {
        when(bucket.store(any(), any())).thenReturn(false);
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Test account")).getId());
        client.put()
                .uri("/user/public-activity")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + accountId))
                .bodyValue(Map.of("visible", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.visible")
                .isEqualTo(true);
        verifyNoInteractions(bucket);
    }

    @Test
    void shouldNotWaitForAnUnreachableBucketOnADisagreement() {
        when(bucket.store(any(), any())).thenReturn(false);
        var scenario = reviews.scenario();
        var result = reviews.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        // The response fence admits a developer with a provider identity.
        var developer = persistUser("journal-disagreer");
        ensureMembership(scenario.workspace(), developer, WorkspaceRole.MEMBER);
        var developerId = accountId(developer);
        var delivered = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(scenario.workspace().getId())
                .reviewId(result.getReviewId())
                .itemId(scenario.item().getId())
                .developerId(developerId)
                .destination(FeedbackDestination.PRACTICE_PAGE)
                .state(FeedbackState.DELIVERED)
                .body("Add a focused regression test.")
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());

        client.put()
                .uri(
                        "/workspaces/{slug}/practices/feedback/{feedbackId}/response",
                        scenario.workspace().getWorkspaceSlug(),
                        delivered.getId())
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + developerId))
                .bodyValue(new FeedbackResponseRequestDTO(
                        null, FeedbackResolution.DISAGREE, "The test already covers this case."))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.resolution")
                .isEqualTo("DISAGREE");
        verifyNoInteractions(bucket);
    }

    @Test
    void shouldNotWaitForAnUnreachableBucketOnACorrection() {
        when(bucket.store(any(), any())).thenReturn(false);
        var scenario = reviews.scenario();
        var result = reviews.result(scenario, ResultOutcome.NOT_MET, Instant.now());

        client.post()
                .uri(
                        "/workspaces/{slug}/practices/reviews/results/{resultId}/marks",
                        scenario.workspace().getWorkspaceSlug(),
                        result.getId())
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "INCORRECT", "reason", "The check read the wrong file"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("INCORRECT");
        verifyNoInteractions(bucket);
    }
}
