package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
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
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Which replies wait for the journal bucket: a synchronous fact's reply ships it, and no other reply waits. */
@Tag("integration")
class PrivacyJournalReplyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @MockitoBean
    private PrivacyJournalObjectStore bucket;

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ReviewIntegrationData reviews;

    @Autowired
    private FeedbackRepository feedback;

    @Test
    void shouldShipASynchronousFactToTheBucketBeforeTheReplyWithAnExternalDatabase() throws Exception {
        // The test database is outside any chart, and the bucket is named on its own, as on managed PostgreSQL.
        try (var minio = new JournalMinio();
                var store = minio.store(JournalMinio.PREFIX)) {
            store.verifyBucket();
            when(bucket.store(any(), any()))
                    .thenAnswer(request -> store.store(request.getArgument(0), request.getArgument(1)));
            var user = persistUser("journal-shipper");
            var workspace =
                    createWorkspace("journal-shipping", "Journal shipping", "journal-shipping", AccountType.ORG, user);
            ensureMembership(workspace, user, WorkspaceRole.OWNER);
            var account = accountId(user);
            var thread = conversations.findOrCreateMain(workspace.getId(), account);
            var entry = entries.append(
                    workspace.getId(),
                    thread.getId(),
                    new ConversationEntryDraft(
                            ConversationEntry.Kind.USER,
                            ConversationEntry.Surface.APP,
                            "Delete this question",
                            null,
                            null,
                            null,
                            null));

            client.delete()
                    .uri(
                            "/workspaces/{slug}/conversation/entries/{entryId}",
                            workspace.getWorkspaceSlug(),
                            entry.getId())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);

            var fact = jdbc.queryForMap("""
                    SELECT entry_id, shipped_at IS NOT NULL AS shipped,
                        (recorded_at AT TIME ZONE 'UTC')::date::text || '/' || entry_id || '.json' AS key
                    FROM privacy_journal WHERE kind = 'ENTRY_DELETED' AND refs->>'entryId' = ?
                    """, entry.getId().toString());
            assertThat(fact.get("shipped")).isEqualTo(true);
            assertThat(minio.object(JournalMinio.PREFIX + fact.get("key")))
                    .contains(Objects.requireNonNull(fact.get("entry_id")).toString(), "ENTRY_DELETED");
        }
    }

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
