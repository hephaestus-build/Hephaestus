package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventView;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class ItemEventServiceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ItemIndexService writer;

    @Autowired
    private ItemEventService service;

    @Autowired
    private ItemEventRepository events;

    @Autowired
    private ItemEventLedgerProperties properties;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountIdentityQuery identities;

    @Test
    void shouldRetainABotProviderActorSeparatelyFromTheRequestingAccount() {
        var provider = providers.saveAndFlush(new IdentityProvider(
                IdentityProviderType.GITHUB, "https://events-" + UUID.randomUUID() + ".example.test"));
        var bot = TestUserFactory.createUser(731L, "event-bot", provider);
        bot.setType(User.Type.BOT);
        bot = users.saveAndFlush(bot);
        var requester = accounts.saveAndFlush(new Account("Requester"));
        var input = input();
        var id = writer.record(new ItemEventInput(
                input.workspaceId(),
                input.itemId(),
                input.name(),
                Objects.requireNonNull(input.itemVersion()),
                input.occurredAt(),
                FoundBy.SYNC,
                bot.getId(),
                requester.getId()));
        assertThat(writer.record(new ItemEventInput(
                        input.workspaceId(),
                        input.itemId(),
                        input.name(),
                        Objects.requireNonNull(input.itemVersion()),
                        input.occurredAt().plusSeconds(5),
                        FoundBy.WEBHOOK,
                        null,
                        null)))
                .isEqualTo(id);
        var recorded = events.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
        assertThat(recorded.getActorUserId()).isEqualTo(bot.getId());
        assertThat(recorded.getRequestedById()).isEqualTo(requester.getId());
        assertThat(identities.resolveAccountId(Objects.requireNonNull(provider.getId()), "731", null))
                .isEmpty();
    }

    @Test
    void shouldPreserveFirstRecordedFactsOnRedelivery() {
        var input = input();
        UUID id = writer.record(input);
        assertThat(writer.record(new ItemEventInput(
                        input.workspaceId(),
                        input.itemId(),
                        input.name(),
                        Objects.requireNonNull(input.itemVersion()),
                        input.occurredAt().plusSeconds(1),
                        FoundBy.SYNC,
                        null,
                        null)))
                .isEqualTo(id);
        var event = events.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
        assertThat(event.getOccurredAt()).isEqualTo(input.occurredAt());
        assertThat(event.getFoundBy()).isEqualTo(FoundBy.WEBHOOK);
    }

    @Test
    void shouldKeepVersionAndOccurrenceWhileWaiting() {
        transactions.executeWithoutResult(status -> {
            var input = input();
            UUID id = writer.record(input);
            assertThat(service.decide(
                            input.workspaceId(),
                            id,
                            ItemEventState.RECORDED,
                            ItemEventState.WAITING,
                            ReviewRequestReason.BUDGET_EXHAUSTED))
                    .isTrue();
            assertThat(service.decide(input.workspaceId(), id, ItemEventState.RECORDED, ItemEventState.STARTED, null))
                    .isFalse();
            var event = events.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
            assertThat(event.getOccurredAt()).isEqualTo(input.occurredAt());
            assertThat(event.getItemVersion()).isEqualTo(input.itemVersion());
            assertThat(event.getRetryAt()).isEqualTo(event.getDecidedAt().plus(properties.pendingRetryAfter()));
            assertThat(service.find(input.workspaceId(), id))
                    .contains(new ItemEventView(
                            input.name(), input.itemVersion(), ItemEventState.WAITING, input.occurredAt()));
        });
    }

    @Test
    void shouldRejectForeignWorkspace() {
        var input = input();
        long foreign = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("event-foreign-" + UUID.randomUUID()))
                .getId();
        assertThatThrownBy(() -> writer.record(new ItemEventInput(
                        foreign,
                        input.itemId(),
                        input.name(),
                        Objects.requireNonNull(input.itemVersion()),
                        input.occurredAt(),
                        FoundBy.WEBHOOK,
                        null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDeduplicateConcurrentRecorders() throws Exception {
        var input = input();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return writer.record(input);
            });
            var second = executor.submit(() -> {
                start.await();
                return writer.record(input);
            });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemEventState.class,
            names = {"STARTED", "WITHHELD", "EXPIRED"})
    void shouldRefuseForeignDecisionsAndKeepTerminalFactsOnReplay(ItemEventState terminalState) {
        transactions.executeWithoutResult(status -> {
            var input = input();
            UUID id = writer.record(input);
            long foreign = workspaces
                    .save(WorkspaceTestFixtures.activeWorkspace(
                            "decision-foreign-" + UUID.randomUUID().toString().substring(0, 12)))
                    .getId();
            assertThat(service.find(foreign, id)).isEmpty();
            assertThat(service.decide(foreign, id, ItemEventState.RECORDED, ItemEventState.STARTED, null))
                    .isFalse();
            var reason = terminalState == ItemEventState.STARTED
                    ? null
                    : terminalState == ItemEventState.EXPIRED
                            ? ReviewRequestReason.PENDING_DEADLINE_EXCEEDED
                            : ReviewRequestReason.OUT_OF_REVIEW_SCOPE;
            assertThat(service.decide(input.workspaceId(), id, ItemEventState.RECORDED, terminalState, reason))
                    .isTrue();
            assertThat(writer.record(input)).isEqualTo(id);
            var terminal =
                    events.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
            assertThat(terminal.getState()).isEqualTo(terminalState);
            assertThat(terminal.getReason()).isEqualTo(reason);
            assertThat(terminal.getOccurredAt()).isEqualTo(input.occurredAt());
            assertThat(service.decide(input.workspaceId(), id, terminalState, ItemEventState.STARTED, null))
                    .isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemEventState.class,
            names = {"RECORDED", "WAITING"})
    void shouldExpireOnlyPendingEventsAndKeepTheirNativeFacts(ItemEventState pendingState) {
        transactions.executeWithoutResult(status -> {
            var pending = input();
            var now = pending.occurredAt();
            var old = new ItemEventInput(
                    pending.workspaceId(),
                    pending.itemId(),
                    pending.name(),
                    Objects.requireNonNull(pending.itemVersion()),
                    now.minus(properties.pendingLapseAfter()).minusSeconds(1),
                    pending.foundBy(),
                    null,
                    null);
            UUID expiredId = writer.record(old);
            if (pendingState != ItemEventState.RECORDED) {
                assertThat(service.decide(
                                old.workspaceId(),
                                expiredId,
                                ItemEventState.RECORDED,
                                pendingState,
                                ReviewRequestReason.BUDGET_EXHAUSTED))
                        .isTrue();
            }
            var current = input();
            UUID currentId = writer.record(current);
            events.expire(
                    now.minus(properties.pendingLapseAfter()), now, ReviewRequestReason.PENDING_DEADLINE_EXCEEDED);
            var expired =
                    events.findByIdAndWorkspaceId(expiredId, old.workspaceId()).orElseThrow();
            assertThat(expired.getState()).isEqualTo(ItemEventState.EXPIRED);
            assertThat(expired.getReason()).isEqualTo(ReviewRequestReason.PENDING_DEADLINE_EXCEEDED);
            assertThat(expired.getItemVersion()).isEqualTo(old.itemVersion());
            assertThat(expired.getOccurredAt()).isEqualTo(old.occurredAt());
            assertThat(events.findByIdAndWorkspaceId(currentId, current.workspaceId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(ItemEventState.RECORDED);
        });
    }

    @Test
    void shouldKeepOneEventOfANamePerVersionWhenTwoDeliveriesCarryOtherSourceKeys() {
        var base = pullRequest();
        UUID first = writer.record(keyed(base, "scm.pull_request.edited", "edit-1"));

        assertThat(writer.record(keyed(base, "scm.pull_request.edited", "edit-2")))
                .isEqualTo(first);
        assertThat(rows(base, "scm.pull_request.edited")).isEqualTo(1);
    }

    @Test
    void shouldRecordOneChecksEventForEachSettledRunSetAtOneVersion() {
        var base = pullRequest();
        UUID first = writer.record(keyed(base, "scm.pull_request.checks_completed", "checks-a"));
        UUID second = writer.record(keyed(base, "scm.pull_request.checks_completed", "checks-b"));

        assertThat(second).isNotEqualTo(first);
        assertThat(writer.record(keyed(base, "scm.pull_request.checks_completed", "checks-a")))
                .isEqualTo(first);
        assertThat(rows(base, "scm.pull_request.checks_completed")).isEqualTo(2);
    }

    /** A pull request item at one version, as the base of the keyed deliveries below. */
    private ItemEventInput pullRequest() {
        long workspace = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("event-" + UUID.randomUUID()))
                .getId();
        var item = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.pull_request"), 1L, null, null));
        return new ItemEventInput(
                workspace,
                item.itemId(),
                new ItemEventName("scm.pull_request.edited"),
                "test-version",
                Instant.parse("2026-10-14T10:00:00Z"),
                FoundBy.WEBHOOK,
                null,
                null);
    }

    private static ItemEventInput keyed(ItemEventInput base, String name, String sourceKey) {
        return new ItemEventInput(
                base.workspaceId(),
                base.itemId(),
                new ItemEventName(name),
                base.itemVersion(),
                null,
                base.occurredAt(),
                FoundBy.WEBHOOK,
                null,
                null,
                null,
                sourceKey);
    }

    private long rows(ItemEventInput base, String name) {
        return events.findAll().stream()
                .filter(event -> event.getWorkspaceId() == base.workspaceId()
                        && event.getItemId() == base.itemId()
                        && name.equals(event.getName()))
                .count();
    }

    private ItemEventInput input() {
        long workspace = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("event-" + UUID.randomUUID()))
                .getId();
        var item = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.issue"), 1L, null, null));
        return new ItemEventInput(
                workspace,
                item.itemId(),
                new ItemEventName("scm.issue.updated"),
                "test-version",
                Instant.parse("2026-10-14T10:00:00Z"),
                FoundBy.WEBHOOK,
                null,
                null);
    }
}
