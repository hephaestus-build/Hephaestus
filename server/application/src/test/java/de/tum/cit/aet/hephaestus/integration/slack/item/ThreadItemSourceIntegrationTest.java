package de.tum.cit.aet.hephaestus.integration.slack.item;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestriction;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.slack.capture.ThreadCaptureSource;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(properties = "hephaestus.integration.slack.enabled=true")
class ThreadItemSourceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ThreadItemSource source;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @PersistenceContext
    private @Nullable EntityManager entities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private SlackThreadRepository threads;

    @Autowired
    private SlackMessageRepository messages;

    @Autowired
    private SlackMonitoredChannelRepository channels;

    @Autowired
    private SlackParticipantConsentRepository consent;

    @Autowired
    private ItemIndexService items;

    @Autowired
    private MembershipService memberships;

    @Test
    void shouldCaptureOnlyThePinnedTenantAndKeepEveryHumanTurn() {
        var fixture = fixture();
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        var pinned = source.pin(fixture.item(), null);
        var capture = source.capture(pinned, request());
        assertThat(new String(
                        Objects.requireNonNull(capture.files().get(ThreadCaptureSource.OUTPUT_KEY)),
                        StandardCharsets.UTF_8))
                .contains("First turn", "Second turn");
        assertThat(pinned.roles()).hasSize(2);
        long foreignWorkspace = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("thread-foreign-" + UUID.randomUUID()))
                .getId();
        assertThat(source.current(new ItemRef(
                        foreignWorkspace,
                        fixture.item().itemId(),
                        ThreadEvents.THREAD,
                        fixture.item().sourceId(),
                        null,
                        null)))
                .isEmpty();
    }

    @Test
    void shouldWithholdTheWholeThreadWhenAContextParticipantOptsOutAfterPinning() {
        var fixture = fixture();
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        var pinned = source.pin(fixture.item(), null);
        consent.optOutOfIngestion(fixture.item().workspaceId(), "U2");
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        var captured = source.capture(pinned, request());
        assertThat(captured.files()).isEmpty();
        assertThat(captured.completeness()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(
                        ThreadCaptureSource.SOURCE,
                        new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
    }

    @Test
    void shouldRespectAnAccountWideNoAiChoiceForTheOtherAuthorsCapturedContext() {
        var fixture = fixture();
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        var pinned = source.pin(fixture.item(), null);
        var contextualAuthor = accounts.findById(fixture.secondAccountId()).orElseThrow();
        contextualAuthor.setAiChoice(AiChoice.NO_AI);
        contextualAuthor.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(contextualAuthor);
        assertThat(source.permits(fixture.item(), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        var capture = source.capture(pinned, request());
        assertThat(capture.files()).isEmpty();
        assertThat(capture.stateOverrides())
                .containsEntry(
                        ThreadCaptureSource.SOURCE,
                        new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
    }

    @Test
    void shouldCountAHumanStandingOverANoAiAuthorUntilAVisibilityControlHidesTheThread() {
        var fixture = fixture();
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        var contextualAuthor = accounts.findById(fixture.secondAccountId()).orElseThrow();
        contextualAuthor.setAiChoice(AiChoice.NO_AI);
        contextualAuthor.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(contextualAuthor);

        for (var purpose : SourceUsePurpose.values()) {
            assertThat(source.permits(fixture.item(), purpose)).isFalse();
            assertThat(source.permits(fixture.item(), purpose, SourceReadAudience.PROCESSING))
                    .isFalse();
        }
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isTrue();
        assertThat(source.current(fixture.item())).isEmpty();

        consent.optOutOfIngestion(fixture.item().workspaceId(), "U2");
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
    }

    @Test
    void shouldRefuseTheHumanStandingWhenANoAiAuthorHasAPermanentErasureFence() {
        var fixture = fixture();
        var author = accounts.findById(fixture.secondAccountId()).orElseThrow();
        author.setAiChoice(AiChoice.NO_AI);
        author.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(author);
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isTrue();

        var erased = new IdentityRestriction();
        erased.setProviderId(fixture.providerId());
        erased.setProviderUserKey("U2");
        erased.setTeamKey(fixture.teamId());
        erased.setKind(IdentityRestriction.Kind.ERASED);
        var entityManager = Objects.requireNonNull(entities);
        entityManager.persist(erased);
        entityManager.flush();

        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
    }

    @Test
    void shouldRefuseTheHumanStandingWhenTheChannelConsentIsNoLongerActive() {
        var fixture = fixture();
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isTrue();
        jdbc.update(
                "UPDATE slack_monitored_channel SET consent_state='PAUSED' WHERE workspace_id=? AND slack_channel_id='C1'",
                fixture.item().workspaceId());
        assertThat(source.permits(fixture.item(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
    }

    @Test
    void anotherActiveSlackTeamCannotReplaceTheThreadSourceConnection() {
        var fixture = fixture();
        var pinned = source.pin(fixture.item(), null);
        var workspace = workspaces.findById(fixture.item().workspaceId()).orElseThrow();
        var original = connections
                .findById(Objects.requireNonNull(pinned.integration().connectionId()))
                .orElseThrow();
        original.setState(IntegrationState.SUSPENDED);
        connections.saveAndFlush(original);
        String otherTeam = "other-" + UUID.randomUUID();
        var other = new Connection(
                workspace,
                IntegrationKind.SLACK,
                otherTeam,
                new ConnectionConfig.SlackConfig(otherTeam, null, null, Set.of()));
        other.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(other);

        assertThat(source.permits(fixture.item(), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertThat(source.current(fixture.item())).isEmpty();
        assertThat(source.capture(pinned, request()).files()).doesNotContainKey(ThreadCaptureSource.OUTPUT_KEY);
    }

    @Autowired
    private SlackThreadEventScheduler scheduler;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldAdvanceOnlyTheCompletedThreadVersionAndNeverRewind(boolean newerTurn) {
        var fixture = fixture();
        var pinned = source.pin(fixture.item(), null);
        long workspaceId = fixture.item().workspaceId();
        long threadId = Objects.requireNonNull(fixture.item().sourceId());
        Instant at = Instant.now();
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .itemId(fixture.item().itemId())
                .kind(ReviewKind.ITEM)
                .itemVersion(pinned.version().value())
                .origin(ReviewOrigin.EVENT)
                .status(ReviewStatus.COMPLETE)
                .orderedAt(at)
                .createdAt(at)
                .admittedAt(at)
                .completedAt(at)
                .build());
        if (newerTurn) {
            jdbc.update(
                    "UPDATE slack_thread SET last_ts='1700000002.000000', message_count=3 WHERE workspace_id=? AND id=?",
                    workspaceId,
                    threadId);
        }
        SlackThreadEventScheduler listener = AopTestUtils.getUltimateTargetObject(scheduler);
        listener.onReviewCompleted(new ReviewCompleted(review.getId(), workspaceId));
        String watermark = jdbc.queryForObject(
                "SELECT last_reviewed_ts FROM slack_thread WHERE workspace_id=? AND id=?",
                String.class,
                workspaceId,
                threadId);
        if (newerTurn) assertThat(watermark).isNull();
        else {
            assertThat(watermark).isEqualTo("1700000001.000000");
            assertThat(threads.advanceReviewWatermark(workspaceId, threadId, "1700000000.000000"))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT last_reviewed_ts FROM slack_thread WHERE workspace_id=? AND id=?",
                            String.class,
                            workspaceId,
                            threadId))
                    .isEqualTo("1700000001.000000");
        }
    }

    private Fixture fixture() {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("thread-source-" + UUID.randomUUID()));
        long workspaceId = workspace.getId();
        String teamId = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                teamId,
                new ConnectionConfig.SlackConfig(teamId, null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setSlackTeamId(teamId);
        channel.setSlackChannelId("C1");
        channel.setConsentState(SlackMonitoredChannel.ConsentState.ACTIVE);
        channels.save(channel);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseGet(() -> providers.save(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com")));
        long first = human(workspace, Objects.requireNonNull(provider.getId()), teamId, "U1");
        long second = human(workspace, Objects.requireNonNull(provider.getId()), teamId, "U2");
        var thread = new SlackThread();
        thread.setWorkspaceId(workspaceId);
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs("1700000000.000000");
        thread.setLastTs("1700000001.000000");
        thread.setMessageCount(2);
        thread.setParticipantAccountIds(new long[] {first, second});
        threads.saveAndFlush(thread);
        message(workspaceId, teamId, first, "U1", "1700000000.000000", "First turn");
        message(workspaceId, teamId, second, "U2", "1700000001.000000", "Second turn");
        var item = items.upsert(new ItemRegistration(workspaceId, ThreadEvents.THREAD, thread.getId(), null, null));
        return new Fixture(item, second, Objects.requireNonNull(provider.getId()), teamId);
    }

    private long human(Workspace workspace, long providerId, String teamId, String subject) {
        var account = accounts.save(new Account("Human " + subject + " " + UUID.randomUUID()));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey(subject);
        identity.setTeamKey(teamId);
        identity.setLinkedVia(Identity.LinkedVia.ROSTER);
        identities.saveAndFlush(identity);
        long accountId = Objects.requireNonNull(account.getId());
        memberships.createMembership(workspace, accountId, Membership.WorkspaceRole.MEMBER);
        return accountId;
    }

    private void message(long workspaceId, String teamId, long authorId, String subject, String ts, String text) {
        var message = new SlackMessage();
        message.setWorkspaceId(workspaceId);
        message.setSlackTeamId(teamId);
        message.setSlackChannelId("C1");
        message.setSlackTs(ts);
        message.setSlackThreadTs("1700000000.000000");
        message.setAuthorSlackUserId(subject);
        message.setAuthorAccountId(authorId);
        message.setText(text);
        messages.saveAndFlush(message);
    }

    private static CaptureRequest request() {
        return new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(ThreadCaptureSource.SOURCE), "3.0.0", 3);
    }

    private record Fixture(ItemRef item, long secondAccountId, long providerId, String teamId) {}
}
