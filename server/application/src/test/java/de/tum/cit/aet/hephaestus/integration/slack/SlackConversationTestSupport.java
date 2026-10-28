package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimedCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

public final class SlackConversationTestSupport {

    public static long accountWithIdentity(
            AccountRepository accounts,
            IdentityRepository identities,
            IdentityProvider provider,
            long nativeId,
            String login) {
        var account = accounts.saveAndFlush(new Account(login));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(Long.toString(nativeId));
        identity.setLinkedVia(Identity.LinkedVia.SIGN_IN);
        identities.saveAndFlush(identity);
        return Objects.requireNonNull(account.getId());
    }

    private final JdbcTemplate jdbc;

    public SlackConversationTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Seed one monitored channel with an explicit consent state. */
    public void seedChannel(long workspaceId, String teamId, String channelId, String consentState) {
        jdbc.update(
                "INSERT INTO slack_monitored_channel (workspace_id, slack_team_id, slack_channel_id, channel_name,"
                        + " consent_state, created_at) VALUES (?, ?, ?, 'engineering', ?, now())",
                workspaceId,
                teamId,
                channelId,
                consentState);
    }

    /**
     * Seed a thread aggregate with an explicit participant member-id set (text array literal → {@code bigint[]}).
     *
     * @param participantArrayLiteral e.g. {@code "{100,101}"}
     */
    public void seedThread(
            long workspaceId,
            String channelId,
            @Nullable String threadTs,
            String lastTs,
            int messageCount,
            String participantArrayLiteral) {
        jdbc.update(
                "INSERT INTO slack_thread (workspace_id, slack_channel_id, slack_thread_ts, first_ts, last_ts,"
                        + " message_count, participant_account_ids, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS"
                        + " bigint[]), now())",
                workspaceId,
                channelId,
                threadTs,
                threadTs,
                lastTs,
                messageCount,
                participantArrayLiteral);
    }

    /** Seed one non-tombstoned message (author {@code U1}). */
    public void seedMessage(
            long workspaceId, String teamId, String channelId, String ts, @Nullable String threadTs, String text) {
        jdbc.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, slack_thread_ts,"
                        + " author_slack_user_id, text, ingested_at) VALUES (?, ?, ?, ?, ?, 'U1', ?, now())",
                workspaceId,
                teamId,
                channelId,
                ts,
                threadTs,
                text);
    }

    private static final ObjectMapper OM = new ObjectMapper();

    public record BoundConversation(UUID resultId, UUID feedbackId) {}

    public static Practice newPractice(PracticeVersionService versions, Workspace workspace, String slugPrefix) {
        PracticeDefinition definition = OM.treeToValue(OM.readTree("""
            {"name":"Test practice","reviewOn":{"events":["chat.thread.settled"],"state":{}},
             "appliesWhen":null,"about":"AUTHOR","reads":{"contract":"3.0.0","sources":[{"source":"slack.conversation.thread","need":"REQUIRED"}]},
             "reviewedBy":{"by":"HEPHAESTUS","humanReason":null,"limits":[]},"criteria":"Give a clear answer.",
             "script":null,"scan":null,"lineNotes":false,"guidance":{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}}
            """), PracticeDefinition.class);
        return versions.create(new PracticeVersionService.NewPractice(
                workspace, slugPrefix + "-" + UUID.randomUUID(), ThreadEvents.THREAD, definition, null, 0, null));
    }

    public static UUID seedResult(
            ReviewRepository reviews,
            ReviewPracticeRepository assignments,
            ReviewItemRepository reviewItems,
            ItemIndexService items,
            ResultRepository results,
            long workspaceId,
            Practice practice,
            ItemKind kind,
            long sourceId,
            long developerId) {
        var item = items.upsert(new ItemRegistration(workspaceId, kind, sourceId, null, null));
        Instant at = Instant.now();
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .itemId(item.itemId())
                .kind(ReviewKind.ITEM)
                .itemVersion("test-version")
                .origin(ReviewOrigin.EVENT)
                .status(ReviewStatus.COMPLETE)
                .orderedAt(at)
                .createdAt(at)
                .admittedAt(at)
                .completedAt(at)
                .build());
        assignments.saveAndFlush(ReviewPractice.builder()
                .workspaceId(workspaceId)
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(ReviewPracticeStatus.DONE)
                .itemsListed(1)
                .listingComplete(true)
                .build());
        var assignment = reviewItems.saveAndFlush(ReviewItem.builder()
                .workspaceId(workspaceId)
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .itemId(item.itemId())
                .itemVersion("test-version")
                .state(ReviewItemState.LISTED)
                .build());
        var result = results.saveAndFlush(Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .itemId(item.itemId())
                .itemVersion("test-version")
                .developerId(developerId)
                .outcome(ResultOutcome.MET)
                .coverage(OM.valueToTree(new ClaimedCoverage(true, null, 1, 1, 1)))
                .recordedAt(at)
                .build());
        assignment.setState(ReviewItemState.JUDGED);
        assignment.setResultId(result.getId());
        reviewItems.saveAndFlush(assignment);
        return result.getId();
    }

    public static BoundConversation seedBoundConversation(
            ReviewRepository reviews,
            ReviewPracticeRepository assignments,
            ReviewItemRepository reviewItems,
            ItemIndexService items,
            ResultRepository results,
            FeedbackRepository feedbacks,
            FeedbackBasisRepository bases,
            long workspaceId,
            Practice practice,
            long threadId,
            long developerId) {
        UUID resultId = seedResult(
                reviews,
                assignments,
                reviewItems,
                items,
                results,
                workspaceId,
                practice,
                ThreadEvents.THREAD,
                threadId,
                developerId);
        var result = results.findById(resultId).orElseThrow();
        var feedback = feedbacks.save(Feedback.builder()
                .id(UUID.randomUUID())
                .reviewId(result.getReviewId())
                .workspaceId(workspaceId)
                .itemId(result.getItemId())
                .developerId(developerId)
                .destination(FeedbackDestination.CONVERSATION)
                .state(FeedbackState.READY)
                .createdAt(Instant.now())
                .build());
        bases.save(FeedbackBasis.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .feedbackId(feedback.getId())
                .resultId(resultId)
                .developerId(developerId)
                .ordinal(0)
                .build());
        return new BoundConversation(resultId, feedback.getId());
    }
}
