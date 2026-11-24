package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Agreed;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Answer;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Attention;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Boundary;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Budget;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Contact;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Controls;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Decision;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Delivery;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Facts;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Initiative;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Message;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Qualification;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.QuietHours;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Reason;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.RequestedResult;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Route;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Routing;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Transport;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Unprompted;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Update;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.WithheldCopy;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.WithholdingReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("unit")
class ContactPolicyTableTest {

    private static final ZonedDateTime MIDDAY = ZonedDateTime.parse("2026-11-24T12:00:00Z");
    private static final ZonedDateTime NIGHT = MIDDAY.withHour(23);
    private static final Instant MORNING = Instant.parse("2026-11-25T08:00:00Z");
    private static final Routing APP_PUSH = new Routing(Set.of(Route.APP), Route.APP, Route.APP, Transport.PUSH);
    private static final Attention UNSEEN = new Attention(false, null);
    private static final Budget EMPTY_BUDGET = new Budget(0, null);
    private static final Controls OPEN =
            new Controls(true, true, false, false, false, QuietHours.DEFAULT, Initiative.SOMETIMES, null);
    private static final Unprompted NEW_PROBLEM =
            new Unprompted(Update.NEW_ADMITTED_PROBLEM, Qualification.CURRENT, false, false, null, null);

    @ParameterizedTest
    @CsvSource({
        "ANSWER,           OPEN,         ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, OPEN,         ELIGIBLE,                  ELIGIBLE",
        "AGREED,           OPEN,         ELIGIBLE,                  ELIGIBLE",
        "UNPROMPTED,       OPEN,         ELIGIBLE,                  ELIGIBLE",
        "ANSWER,           INSTANCE_OFF, ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, INSTANCE_OFF, ELIGIBLE,                  STARTED_MESSAGES_DISABLED",
        "AGREED,           INSTANCE_OFF, STARTED_MESSAGES_DISABLED, NO_MESSAGE",
        "UNPROMPTED,       INSTANCE_OFF, STARTED_MESSAGES_DISABLED, NO_MESSAGE",
        "ANSWER,           WORKSPACE_OFF,ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, WORKSPACE_OFF,ELIGIBLE,                  STARTED_MESSAGES_DISABLED",
        "AGREED,           WORKSPACE_OFF,STARTED_MESSAGES_DISABLED, NO_MESSAGE",
        "UNPROMPTED,       WORKSPACE_OFF,STARTED_MESSAGES_DISABLED, NO_MESSAGE",
        "ANSWER,           PAUSE,        ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, PAUSE,        ELIGIBLE,                  PAUSED",
        "AGREED,           PAUSE,        PAUSED,                    NO_MESSAGE",
        "UNPROMPTED,       PAUSE,        PAUSED,                    NO_MESSAGE",
        "ANSWER,           MUTE,         ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, MUTE,         ELIGIBLE,                  MUTED",
        "AGREED,           MUTE,         MUTED,                     NO_MESSAGE",
        "UNPROMPTED,       MUTE,         MUTED,                     NO_MESSAGE",
        "ANSWER,           QUIET,        ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, QUIET,        ELIGIBLE,                  ELIGIBLE",
        "AGREED,           QUIET,        ELIGIBLE,                  QUIET_HOURS",
        "UNPROMPTED,       QUIET,        ELIGIBLE,                  QUIET_HOURS",
        "ANSWER,           ACTIVE,       ELIGIBLE,                  ACTIVE",
        "REQUESTED_RESULT, ACTIVE,       ELIGIBLE,                  ACTIVE",
        "AGREED,           ACTIVE,       ELIGIBLE,                  ACTIVE",
        "UNPROMPTED,       ACTIVE,       ELIGIBLE,                  ACTIVE",
        "ANSWER,           SEEN,         ELIGIBLE,                  SEEN",
        "REQUESTED_RESULT, SEEN,         ELIGIBLE,                  SEEN",
        "AGREED,           SEEN,         ELIGIBLE,                  SEEN",
        "UNPROMPTED,       SEEN,         ELIGIBLE,                  SEEN",
        "ANSWER,           FULL_BUDGET,  ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, FULL_BUDGET,  ELIGIBLE,                  ELIGIBLE",
        "AGREED,           FULL_BUDGET,  ELIGIBLE,                  ELIGIBLE",
        "UNPROMPTED,       FULL_BUDGET,  ELIGIBLE,                  DAILY_LIMIT",
        "ANSWER,           ONLY_AGREED,  ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, ONLY_AGREED,  ELIGIBLE,                  ELIGIBLE",
        "AGREED,           ONLY_AGREED,  ELIGIBLE,                  ELIGIBLE",
        "UNPROMPTED,       ONLY_AGREED,  ELIGIBLE,                  INITIATIVE",
        "ANSWER,           LIGHTER_TOUCH,ELIGIBLE,                  ELIGIBLE",
        "REQUESTED_RESULT, LIGHTER_TOUCH,ELIGIBLE,                  ELIGIBLE",
        "AGREED,           LIGHTER_TOUCH,ELIGIBLE,                  ELIGIBLE",
        "UNPROMPTED,       LIGHTER_TOUCH,ELIGIBLE,                  INITIATIVE",
        "ANSWER,           SILENT,       ELIGIBLE,                  SILENT_MODE",
        "REQUESTED_RESULT, SILENT,       ELIGIBLE,                  SILENT_MODE",
        "AGREED,           SILENT,       ELIGIBLE,                  SILENT_MODE",
        "UNPROMPTED,       SILENT,       ELIGIBLE,                  SILENT_MODE",
        "ANSWER,           NO_ROUTE,     ROUTE_UNAVAILABLE,         NO_MESSAGE",
        "REQUESTED_RESULT, NO_ROUTE,     ROUTE_UNAVAILABLE,         NO_MESSAGE",
        "AGREED,           NO_ROUTE,     ROUTE_UNAVAILABLE,         NO_MESSAGE",
        "UNPROMPTED,       NO_ROUTE,     ROUTE_UNAVAILABLE,         NO_MESSAGE",
    })
    void shouldApplyTheContactTable(Kind kind, Signal signal, Reason message, Reason delivery) {
        ZonedDateTime time = signal == Signal.QUIET ? NIGHT : MIDDAY;
        Instant now = time.toInstant();
        Controls controls = new Controls(
                signal != Signal.INSTANCE_OFF,
                signal != Signal.WORKSPACE_OFF,
                signal == Signal.PAUSE,
                signal == Signal.MUTE,
                signal == Signal.SILENT,
                QuietHours.DEFAULT,
                signal == Signal.ONLY_AGREED ? Initiative.ONLY_WHEN_AGREED : Initiative.SOMETIMES,
                signal == Signal.LIGHTER_TOUCH ? time.toLocalDate().plusDays(1) : null);
        Routing routing =
                signal == Signal.NO_ROUTE ? new Routing(Set.of(), null, Route.SLACK_DM, Transport.PUSH) : APP_PUSH;
        Attention attention = new Attention(signal == Signal.SEEN, signal == Signal.ACTIVE ? now : null);
        Budget budget = signal == Signal.FULL_BUDGET ? new Budget(1, now.minusSeconds(10)) : EMPTY_BUDGET;
        Decision result = ContactPolicy.decide(
                new Facts(kind.contactAt(now), Boundary.COMMIT, time, routing, controls, attention, budget));
        Transport transport = switch (delivery) {
            case ELIGIBLE, QUIET_HOURS -> Transport.PUSH;
            default -> null;
        };
        Instant at = switch (delivery) {
            case ELIGIBLE -> now;
            case QUIET_HOURS -> MORNING;
            default -> null;
        };

        assertThat(result)
                .isEqualTo(new Decision(
                        new Message(message, null),
                        signal == Signal.NO_ROUTE ? null : Route.APP,
                        new Delivery(delivery, transport, at)));
    }

    @ParameterizedTest
    @CsvSource({
        "-1,   BEFORE_SLOT",
        "0,    ELIGIBLE",
        "1,    ELIGIBLE",
        "1799, ELIGIBLE",
        "1800, GRACE_ENDED",
        "1801, GRACE_ENDED",
    })
    void shouldApplyTheAgreedSlotAndGraceAtDispatchAndCommit(long seconds, Reason reason) {
        Instant slot = MIDDAY.toInstant();
        Agreed agreed = new Agreed(true, slot, false);
        ZonedDateTime time = MIDDAY.plusSeconds(seconds);

        for (Boundary boundary : Set.of(Boundary.DISPATCH, Boundary.COMMIT)) {
            Decision result =
                    ContactPolicy.decide(new Facts(agreed, boundary, time, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET));
            assertThat(result.message()).isEqualTo(new Message(reason, reason == Reason.BEFORE_SLOT ? slot : null));
            assertThat(result.delivery().at()).isEqualTo(reason == Reason.ELIGIBLE ? time.toInstant() : null);
        }
    }

    @Test
    void shouldExemptAnExplicitQuietSlotAndRecheckADeferredNotificationAfterGrace() {
        Agreed explicit = new Agreed(true, NIGHT.toInstant(), true);
        Agreed ordinary = new Agreed(true, NIGHT.toInstant(), false);
        assertThat(decide(explicit, NIGHT).delivery())
                .isEqualTo(new Delivery(Reason.ELIGIBLE, Transport.PUSH, NIGHT.toInstant()));
        assertThat(decide(ordinary, NIGHT).delivery())
                .isEqualTo(new Delivery(Reason.QUIET_HOURS, Transport.PUSH, MORNING));

        ZonedDateTime morning = MORNING.atZone(NIGHT.getZone());
        Facts send = new Facts(ordinary, Boundary.SEND, morning, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(send).delivery())
                .isEqualTo(new Delivery(Reason.ELIGIBLE, Transport.PUSH, MORNING));
        assertThat(decide(new Agreed(false, NIGHT.toInstant(), true), NIGHT)
                        .message()
                        .reason())
                .isEqualTo(Reason.OUTSIDE_AGREEMENT);
    }

    @ParameterizedTest
    @CsvSource({
        "29, ANSWER_DELAY",
        "30, ELIGIBLE",
        "31, ELIGIBLE",
        "3629, ELIGIBLE",
        "3630, ANSWER_EXPIRED",
        "3631, ANSWER_EXPIRED"
    })
    void shouldSendAnUnreadAnswerInsideItsNotificationWindow(long seconds, Reason reason) {
        Instant ended = MIDDAY.toInstant();
        ZonedDateTime time = MIDDAY.plusSeconds(seconds);
        assertThat(decide(new Answer(ended), time).delivery())
                .isEqualTo(
                        switch (reason) {
                            case ANSWER_DELAY -> new Delivery(reason, Transport.PUSH, ended.plusSeconds(30));
                            case ELIGIBLE -> new Delivery(reason, Transport.PUSH, time.toInstant());
                            default -> new Delivery(reason, null, null);
                        });
        assertThat(decide(new Answer(null), time).delivery()).isEqualTo(new Delivery(Reason.TURN_OPEN, null, null));
    }

    @ParameterizedTest
    @CsvSource({"-1, ACTIVE", "0, ACTIVE", "119, ACTIVE", "120, ELIGIBLE", "121, ELIGIBLE"})
    void shouldSuppressPushOnlyForARecentForegroundConnection(long secondsAgo, Reason reason) {
        Instant now = MIDDAY.toInstant();
        Facts facts = new Facts(
                NEW_PROBLEM,
                Boundary.SEND,
                MIDDAY,
                APP_PUSH,
                OPEN,
                new Attention(false, now.minusSeconds(secondsAgo)),
                EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(facts).delivery().reason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @CsvSource({
        "21:59:59, ELIGIBLE",
        "22:00:00, QUIET_HOURS",
        "22:00:01, QUIET_HOURS",
        "00:00:00, QUIET_HOURS",
        "07:59:59, QUIET_HOURS",
        "08:00:00, ELIGIBLE",
        "08:00:01, ELIGIBLE",
    })
    void shouldDelayOnlyTheNotificationOverTheDefaultQuietWindow(LocalTime time, Reason reason) {
        ZonedDateTime now = MIDDAY.with(time);
        Instant expectedAt = reason == Reason.QUIET_HOURS
                ? (time.getHour() < 8 ? MORNING.minusSeconds(86400) : MORNING)
                : now.toInstant();
        Decision decision = decide(NEW_PROBLEM, now);

        assertThat(decision.message()).isEqualTo(new Message(Reason.ELIGIBLE, null));
        assertThat(decision.delivery()).isEqualTo(new Delivery(reason, Transport.PUSH, expectedAt));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-03-29T01:00+01:00[Europe/Berlin], 2026-03-29T01:30:00Z",
        "2026-03-29T03:30+02:00[Europe/Berlin],",
        "2026-10-25T02:29+02:00[Europe/Berlin], 2026-10-25T00:30:00Z",
        "2026-10-25T02:30+02:00[Europe/Berlin],",
        "2026-10-25T02:00+01:00[Europe/Berlin],",
    })
    void shouldResolveQuietHoursOnTheLocalClockAcrossDaylightSaving(ZonedDateTime time, @Nullable Instant end) {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(2, 30));
        assertThat(quietHours.endIfQuiet(time)).isEqualTo(end);
    }

    @Test
    void shouldApplySameDayAndDisabledQuietWindows() {
        QuietHours daytime = new QuietHours(LocalTime.of(12, 0), LocalTime.of(13, 0));
        assertThat(daytime.endIfQuiet(MIDDAY.minusNanos(1))).isNull();
        assertThat(daytime.endIfQuiet(MIDDAY)).isEqualTo(MIDDAY.plusHours(1).toInstant());
        assertThat(daytime.endIfQuiet(MIDDAY.plusHours(1).minusNanos(1)))
                .isEqualTo(MIDDAY.plusHours(1).toInstant());
        assertThat(daytime.endIfQuiet(MIDDAY.plusHours(1))).isNull();
        assertThat(new QuietHours(LocalTime.NOON, LocalTime.NOON).endIfQuiet(MIDDAY))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "ONLY_WHEN_AGREED, 0, 10800, INITIATIVE",
        "SOMETIMES,        0,     0, ELIGIBLE",
        "SOMETIMES,        1, 10800, DAILY_LIMIT",
        "OFTEN,            0, 10799, NOTIFICATION_SPACING",
        "OFTEN,            2, 10799, NOTIFICATION_SPACING",
        "OFTEN,            2, 10800, ELIGIBLE",
        "OFTEN,            2, 10801, ELIGIBLE",
        "OFTEN,            3, 10800, DAILY_LIMIT",
    })
    void shouldCountOnlyUnpromptedNotificationsAcrossChannels(
            Initiative initiative, int sentToday, long secondsAgo, Reason reason) {
        ZonedDateTime time = sentToday == 0 ? MIDDAY.withHour(0) : MIDDAY;
        Instant lastSent = time.toInstant().minusSeconds(secondsAgo);
        Controls controls = new Controls(
                true, true, false, false, false, new QuietHours(LocalTime.NOON, LocalTime.NOON), initiative, null);
        for (Transport transport : Set.of(Transport.PUSH, Transport.EMAIL, Transport.SLACK_DM)) {
            Routing routing = transport == Transport.SLACK_DM
                    ? new Routing(Set.of(Route.APP, Route.SLACK_DM), Route.SLACK_DM, Route.SLACK_DM, Transport.PUSH)
                    : new Routing(Set.of(Route.APP), Route.APP, Route.APP, transport);
            Decision decision = ContactPolicy.decide(new Facts(
                    NEW_PROBLEM, Boundary.COMMIT, time, routing, controls, UNSEEN, new Budget(sentToday, lastSent)));
            assertThat(decision.message().eligible()).isTrue();
            assertThat(decision.delivery())
                    .isEqualTo(
                            switch (reason) {
                                case ELIGIBLE -> new Delivery(reason, transport, time.toInstant());
                                case NOTIFICATION_SPACING ->
                                    new Delivery(reason, transport, lastSent.plusSeconds(10800));
                                default -> new Delivery(reason, null, null);
                            });
        }
    }

    @Test
    void shouldEndLighterTouchAtTheStartOfItsLocalDate() {
        ZonedDateTime midnight = MIDDAY.toLocalDate().plusDays(1).atStartOfDay(MIDDAY.getZone());
        Controls controls = new Controls(
                true,
                true,
                false,
                false,
                false,
                new QuietHours(LocalTime.NOON, LocalTime.NOON),
                Initiative.OFTEN,
                midnight.toLocalDate());
        Facts before =
                new Facts(NEW_PROBLEM, Boundary.SEND, midnight.minusNanos(1), APP_PUSH, controls, UNSEEN, EMPTY_BUDGET);
        Facts after = new Facts(NEW_PROBLEM, Boundary.SEND, midnight, APP_PUSH, controls, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(before).delivery().reason()).isEqualTo(Reason.INITIATIVE);
        assertThat(ContactPolicy.decide(after).delivery().reason()).isEqualTo(Reason.ELIGIBLE);
    }

    @ParameterizedTest
    @CsvSource({
        "NONE,                           CURRENT, NO_TRIGGER",
        "NEW_ADMITTED_PROBLEM,           CURRENT, ELIGIBLE",
        "NEW_ADMITTED_PROBLEM,           STALE,   UNQUALIFIED",
        "NEW_ADMITTED_PROBLEM,           MISSING, UNQUALIFIED",
        "MATERIAL_CHANGE_ON_AGREED_GOAL, MISSING, ELIGIBLE",
        "AWAITED_RESULT,                  STALE,   ELIGIBLE",
    })
    void shouldAdmitOnlyQualifiedUnpromptedUpdates(Update update, Qualification qualification, Reason expected) {
        assertThat(decide(new Unprompted(update, qualification, false, false, null, null), MIDDAY)
                        .message()
                        .reason())
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(WithholdingReason.class)
    void shouldAdmitOnlyWithholdingNoticesThatHaveAnEligibleReasonAndSeverity(WithholdingReason reason) {
        for (Severity severity : Severity.values()) {
            Contact contact =
                    new Unprompted(new WithheldCopy(reason, severity), Qualification.MISSING, false, false, null, null);
            boolean qualifies = reason != WithholdingReason.OTHER
                    && Set.of(Severity.CRITICAL, Severity.MAJOR).contains(severity);
            assertThat(decide(contact, MIDDAY).message().reason())
                    .isEqualTo(qualifies ? Reason.ELIGIBLE : Reason.NO_TRIGGER);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,  true,  true,  true,  STARTED_MESSAGES_DISABLED",
        "false, true,  true,  true,  PAUSED",
        "false, false, true,  true,  STUDY_HOLD",
        "false, false, false, true,  DUPLICATE",
        "false, false, false, false, UNQUALIFIED",
    })
    void shouldGiveTheFirstMessageRefusalWhenSignalsConflict(
            boolean disabled, boolean paused, boolean held, boolean duplicate, Reason reason) {
        Contact contact = new Unprompted(Update.NEW_ADMITTED_PROBLEM, Qualification.STALE, held, duplicate, null, null);
        Controls controls = new Controls(
                !disabled, true, paused, false, false, QuietHours.DEFAULT, Initiative.ONLY_WHEN_AGREED, null);
        Facts facts = new Facts(
                contact,
                Boundary.COMMIT,
                NIGHT,
                APP_PUSH,
                controls,
                new Attention(true, NIGHT.toInstant()),
                EMPTY_BUDGET);

        assertThat(ContactPolicy.decide(facts))
                .isEqualTo(new Decision(
                        new Message(reason, null), Route.APP, new Delivery(Reason.NO_MESSAGE, null, null)));
    }

    @ParameterizedTest
    @CsvSource({"119, IDLE_ONLY", "120, ELIGIBLE", "121, ELIGIBLE"})
    void shouldApplyIdleOnlyAtDispatch(long idleSeconds, Reason reason) {
        Instant lastMessage = MIDDAY.toInstant().minusSeconds(idleSeconds);
        Contact contact = new Unprompted(Update.AWAITED_RESULT, Qualification.MISSING, false, false, lastMessage, null);
        Facts dispatch = new Facts(contact, Boundary.DISPATCH, MIDDAY, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(dispatch).message())
                .isEqualTo(new Message(reason, reason == Reason.IDLE_ONLY ? lastMessage.plusSeconds(120) : null));
        assertThat(decide(contact, MIDDAY).message().eligible()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"59, IDLE_ONLY, 60", "60, IDLE_ONLY, 60", "61, OUTSIDE_ACTIVE_HOURS, 61"})
    void shouldHoldAnOptionalItemUntilBothStartGatesOpen(long activeIn, Reason reason, long resumeIn) {
        Instant now = MIDDAY.toInstant();
        Contact contact = new Unprompted(
                Update.AWAITED_RESULT,
                Qualification.MISSING,
                false,
                false,
                now.minusSeconds(60),
                now.plusSeconds(activeIn));
        Facts before = new Facts(contact, Boundary.DISPATCH, MIDDAY, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET);
        Facts after = new Facts(
                contact, Boundary.DISPATCH, MIDDAY.plusSeconds(resumeIn), APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(before).message()).isEqualTo(new Message(reason, now.plusSeconds(resumeIn)));
        assertThat(ContactPolicy.decide(after).message().eligible()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "true,  true,  ,        SLACK_DM, SLACK_DM",
        "true,  false, ,        SLACK_DM, APP",
        "true,  true,  APP,     SLACK_DM, APP",
        "true,  false, SLACK_DM, APP,     ",
        "true,  true,  SLACK_DM, APP,     SLACK_DM",
        "false, true,  ,        APP,      ",
        "false, false, ,        SLACK_DM, ",
    })
    void shouldHonorThePinAndChooseOnlyAnAllowedRoute(
            boolean appAllowed, boolean slackAllowed, @Nullable Route pin, Route preferred, @Nullable Route expected) {
        Set<Route> allowed = appAllowed
                ? (slackAllowed ? Set.of(Route.APP, Route.SLACK_DM) : Set.of(Route.APP))
                : (slackAllowed ? Set.of(Route.SLACK_DM) : Set.of());
        Routing routing = new Routing(allowed, pin, preferred, Transport.PUSH);
        Decision decision = ContactPolicy.decide(
                new Facts(NEW_PROBLEM, Boundary.DISPATCH, MIDDAY, routing, OPEN, UNSEEN, EMPTY_BUDGET));
        assertThat(decision.route()).isEqualTo(expected);
        assertThat(decision.message().reason())
                .isEqualTo(expected == null ? Reason.ROUTE_UNAVAILABLE : Reason.ELIGIBLE);
    }

    @Test
    void shouldHoldStartedSlackDeliveryButReplyImmediatelyToThePersonsMessage() {
        Routing slack = new Routing(Set.of(Route.APP, Route.SLACK_DM), Route.SLACK_DM, Route.APP, Transport.PUSH);
        Instant now = NIGHT.toInstant();
        Attention foreground = new Attention(false, now);
        Facts started = new Facts(
                NEW_PROBLEM, Boundary.SEND, NIGHT, slack, OPEN, foreground, new Budget(1, now.minusSeconds(1)));
        assertThat(ContactPolicy.decide(started))
                .isEqualTo(new Decision(
                        new Message(Reason.ELIGIBLE, null),
                        Route.SLACK_DM,
                        new Delivery(Reason.QUIET_HOURS, Transport.SLACK_DM, MORNING)));
        Facts requested = new Facts(
                new RequestedResult(),
                Boundary.SEND,
                NIGHT,
                slack,
                OPEN,
                foreground,
                new Budget(1, now.minusSeconds(1)));
        assertThat(ContactPolicy.decide(requested).delivery())
                .isEqualTo(new Delivery(Reason.ELIGIBLE, Transport.SLACK_DM, now));

        Controls paused =
                new Controls(true, true, true, true, false, QuietHours.DEFAULT, Initiative.ONLY_WHEN_AGREED, null);
        Facts reply =
                new Facts(new Answer(now), Boundary.SEND, NIGHT, slack, paused, new Attention(true, now), EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(reply).delivery())
                .isEqualTo(new Delivery(Reason.SLACK_REPLY, Transport.SLACK_DM, now));
        Facts queued = new Facts(new RequestedResult(), Boundary.SEND, NIGHT, slack, paused, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(queued))
                .isEqualTo(new Decision(
                        new Message(Reason.ELIGIBLE, null), Route.SLACK_DM, new Delivery(Reason.PAUSED, null, null)));
    }

    @Test
    void shouldRefuseToChooseANewRouteAtSend() {
        Routing unpinned = new Routing(Set.of(Route.APP, Route.SLACK_DM), null, Route.SLACK_DM, Transport.PUSH);
        Routing lostSlack = new Routing(Set.of(Route.APP), Route.SLACK_DM, Route.SLACK_DM, Transport.PUSH);
        for (Routing routing : Set.of(unpinned, lostSlack)) {
            Decision decision = ContactPolicy.decide(
                    new Facts(new RequestedResult(), Boundary.SEND, MIDDAY, routing, OPEN, UNSEEN, EMPTY_BUDGET));
            assertThat(decision)
                    .isEqualTo(new Decision(
                            new Message(Reason.ROUTE_UNAVAILABLE, null),
                            null,
                            new Delivery(Reason.NO_MESSAGE, null, null)));
        }
    }

    @Test
    void shouldRequireTheOriginalRouteWhenDispatchingAnAnswerOrRequestedResult() {
        Routing unpinned = new Routing(Set.of(Route.APP, Route.SLACK_DM), null, Route.SLACK_DM, Transport.PUSH);
        for (Contact contact : Set.of(new Answer(null), new RequestedResult())) {
            Decision decision = ContactPolicy.decide(
                    new Facts(contact, Boundary.DISPATCH, MIDDAY, unpinned, OPEN, UNSEEN, EMPTY_BUDGET));
            assertThat(decision.message().reason()).isEqualTo(Reason.ROUTE_UNAVAILABLE);
        }
    }

    @Test
    void shouldRecheckForegroundWhenTheAnswerDelayEnds() {
        Instant ended = MIDDAY.toInstant();
        Answer answer = new Answer(ended);
        Instant ready = ended.plusSeconds(30);
        Attention active = new Attention(false, ended);
        Facts commit = new Facts(answer, Boundary.COMMIT, MIDDAY, APP_PUSH, OPEN, active, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(commit).delivery())
                .isEqualTo(new Delivery(Reason.ANSWER_DELAY, Transport.PUSH, ready));

        ZonedDateTime due = MIDDAY.plusSeconds(30);
        Facts stillActive = new Facts(answer, Boundary.SEND, due, APP_PUSH, OPEN, active, EMPTY_BUDGET);
        Facts left = new Facts(answer, Boundary.SEND, due, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET);
        assertThat(ContactPolicy.decide(stillActive).delivery()).isEqualTo(new Delivery(Reason.ACTIVE, null, null));
        assertThat(ContactPolicy.decide(left).delivery())
                .isEqualTo(new Delivery(Reason.ELIGIBLE, Transport.PUSH, ready));
    }

    @Test
    void shouldKeepAnAppOnlyTaskInTheAppAndAllowOneFixedSlackNotice() {
        Instant now = NIGHT.toInstant();
        Routing routing = new Routing(Set.of(Route.APP), Route.APP, Route.APP, Transport.SLACK_NOTICE);
        Facts requested = new Facts(new RequestedResult(), Boundary.COMMIT, NIGHT, routing, OPEN, UNSEEN, EMPTY_BUDGET);
        Facts agreed =
                new Facts(new Agreed(true, now, false), Boundary.COMMIT, NIGHT, routing, OPEN, UNSEEN, EMPTY_BUDGET);
        Facts unprompted = new Facts(NEW_PROBLEM, Boundary.COMMIT, NIGHT, routing, OPEN, UNSEEN, EMPTY_BUDGET);

        assertThat(ContactPolicy.decide(requested))
                .isEqualTo(new Decision(
                        new Message(Reason.ELIGIBLE, null),
                        Route.APP,
                        new Delivery(Reason.ELIGIBLE, Transport.SLACK_NOTICE, now)));
        assertThat(ContactPolicy.decide(agreed))
                .isEqualTo(new Decision(
                        new Message(Reason.ELIGIBLE, null),
                        Route.APP,
                        new Delivery(Reason.QUIET_HOURS, Transport.SLACK_NOTICE, MORNING)));
        assertThat(ContactPolicy.decide(unprompted).delivery())
                .isEqualTo(new Delivery(Reason.NO_TRANSPORT, null, null));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldKeepTheMessageAndRouteIndependentOfNotificationControls(Kind kind) {
        for (int minute = 0; minute < 24 * 60; minute++) {
            ZonedDateTime time = MIDDAY.with(LocalTime.MIDNIGHT).plusMinutes(minute);
            Instant now = time.toInstant();
            Contact contact = kind.contactAt(now);
            Decision base = decide(contact, time);
            for (Initiative initiative : Initiative.values()) {
                Controls restricted =
                        new Controls(true, true, false, false, true, QuietHours.DEFAULT, initiative, LocalDate.MAX);
                Decision changed = ContactPolicy.decide(new Facts(
                        contact,
                        Boundary.COMMIT,
                        time,
                        APP_PUSH,
                        restricted,
                        new Attention(true, now),
                        new Budget(3, now)));
                assertThat(changed.message()).isEqualTo(base.message());
                assertThat(changed.route()).isEqualTo(base.route());
                assertThat(changed.delivery()).isEqualTo(new Delivery(Reason.SILENT_MODE, null, null));
            }
        }
    }

    private static Decision decide(Contact contact, ZonedDateTime time) {
        return ContactPolicy.decide(new Facts(contact, Boundary.COMMIT, time, APP_PUSH, OPEN, UNSEEN, EMPTY_BUDGET));
    }

    private enum Kind {
        ANSWER,
        REQUESTED_RESULT,
        AGREED,
        UNPROMPTED;

        Contact contactAt(Instant now) {
            return switch (this) {
                case ANSWER -> new Answer(now.minusSeconds(30));
                case REQUESTED_RESULT -> new RequestedResult();
                case AGREED -> new Agreed(true, now, false);
                case UNPROMPTED -> NEW_PROBLEM;
            };
        }
    }

    private enum Signal {
        OPEN,
        INSTANCE_OFF,
        WORKSPACE_OFF,
        PAUSE,
        MUTE,
        QUIET,
        ACTIVE,
        SEEN,
        FULL_BUDGET,
        ONLY_AGREED,
        LIGHTER_TOUCH,
        SILENT,
        NO_ROUTE,
    }
}
