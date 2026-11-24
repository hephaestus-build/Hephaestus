package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Contact rules from ADR 0139. Each boundary supplies its current facts. */
final class ContactPolicy {

    private static final Duration ACTIVITY_WINDOW = Duration.ofMinutes(2);
    private static final Duration ANSWER_DELAY = Duration.ofSeconds(30);
    private static final Duration ANSWER_LIFETIME = Duration.ofHours(1);
    private static final Duration CHECK_IN_GRACE = Duration.ofMinutes(30);
    private static final Duration OFTEN_SPACING = Duration.ofHours(3);

    private ContactPolicy() {}

    sealed interface Contact permits Answer, RequestedResult, Agreed, Unprompted {}

    record Answer(@Nullable Instant turnEndedAt) implements Contact {}

    record RequestedResult() implements Contact {}

    record Agreed(boolean inScope, Instant dueAt, boolean slotSetInsideQuietHours) implements Contact {}

    sealed interface Trigger permits Update, WithheldCopy {}

    enum Update implements Trigger {
        NONE,
        NEW_ADMITTED_PROBLEM,
        MATERIAL_CHANGE_ON_AGREED_GOAL,
        AWAITED_RESULT,
    }

    enum WithholdingReason {
        DEADLINE_PASSED,
        SECOND_GUARDRAIL_REFUSAL,
        APPROVAL_EXPIRED,
        OTHER,
    }

    record WithheldCopy(WithholdingReason reason, Severity severity) implements Trigger {
        boolean qualifies() {
            return reason != WithholdingReason.OTHER && (severity == Severity.CRITICAL || severity == Severity.MAJOR);
        }
    }

    enum Qualification {
        CURRENT,
        MISSING,
        STALE,
    }

    /* The duplicate check excludes the current logical message when it runs again. */
    record Unprompted(
            Trigger trigger,
            Qualification qualification,
            boolean heldResult,
            boolean duplicateProblemVersion,
            @Nullable Instant lastMessageWithIdleOnly,
            @Nullable Instant nextActiveHoursStart)
            implements Contact {}

    enum Route {
        APP,
        SLACK_DM,
    }

    enum Transport {
        PUSH,
        EMAIL,
        SLACK_DM,
        SLACK_NOTICE,
    }

    /*
     * Allowed routes cover the whole batch. Slack needs a pin or an explicit preference.
     * Pin the route of an answer or a requested result. After claim, pass the claimed route as the pin.
     * At send, use the transport of the queued effect if it is still available. SLACK_NOTICE is a
     * fixed notice with an app link for an app-only task requested or agreed in Slack.
     */
    record Routing(
            Set<Route> allowed,
            @Nullable Route pinned,
            Route preferred,
            @Nullable Transport appNotification) {
        Routing {
            allowed = Set.copyOf(allowed);
            if (appNotification == Transport.SLACK_DM) {
                throw new IllegalArgumentException("Slack delivery requires the Slack DM route");
            }
        }

        @Nullable
        Route select(boolean mayChooseRoute) {
            if (pinned != null) {
                return allowed.contains(pinned) ? pinned : null;
            }
            if (!mayChooseRoute) {
                return null;
            }
            if (allowed.contains(preferred)) {
                return preferred;
            }
            return allowed.contains(Route.APP) ? Route.APP : null;
        }
    }

    enum Initiative {
        ONLY_WHEN_AGREED(0),
        SOMETIMES(1),
        OFTEN(3);

        private final int dailyLimit;

        Initiative(int dailyLimit) {
            this.dailyLimit = dailyLimit;
        }
    }

    record Controls(
            boolean instanceAllowsStartedMessages,
            boolean workspaceAllowsStartedMessages,
            boolean paused,
            boolean muted,
            boolean silentMode,
            QuietHours quietHours,
            Initiative initiative,
            @Nullable LocalDate lighterTouchUntil) {}

    /* Count one logical notification across all channels, on the local date of the evaluation. */
    record Budget(int sentToday, @Nullable Instant lastSentAt) {
        Budget {
            if (sentToday < 0 || (sentToday > 0 && lastSentAt == null)) {
                throw new IllegalArgumentException("Invalid unprompted notification history");
            }
        }
    }

    /* The connection is for a foreground, live client in this workspace. */
    record Attention(boolean seen, @Nullable Instant foregroundConnectedAt) {}

    /* A half-open interval in local wall time. Equal endpoints disable quiet hours. */
    record QuietHours(LocalTime start, LocalTime end) {
        static final QuietHours DEFAULT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0));

        @Nullable
        Instant endIfQuiet(ZonedDateTime time) {
            LocalDate date = time.toLocalDate();
            Instant endToday = endOfWindow(date, time);
            return endToday == null && end.isBefore(start) ? endOfWindow(date.minusDays(1), time) : endToday;
        }

        @Nullable
        private Instant endOfWindow(LocalDate date, ZonedDateTime time) {
            LocalDate endDate = end.isBefore(start) ? date.plusDays(1) : date;
            Instant startAt = date.atTime(start).atZone(time.getZone()).toInstant();
            Instant endAt = endDate.atTime(end).atZone(time.getZone()).toInstant();
            Instant now = time.toInstant();
            return !now.isBefore(startAt) && now.isBefore(endAt) ? endAt : null;
        }
    }

    enum Boundary {
        DISPATCH,
        COMMIT,
        SEND,
    }

    record Facts(
            Contact contact,
            Boundary boundary,
            ZonedDateTime now,
            Routing routing,
            Controls controls,
            Attention attention,
            Budget budget) {}

    enum Reason {
        ELIGIBLE,
        ROUTE_UNAVAILABLE,
        STARTED_MESSAGES_DISABLED,
        PAUSED,
        MUTED,
        OUTSIDE_AGREEMENT,
        BEFORE_SLOT,
        GRACE_ENDED,
        STUDY_HOLD,
        DUPLICATE,
        NO_TRIGGER,
        UNQUALIFIED,
        IDLE_ONLY,
        OUTSIDE_ACTIVE_HOURS,
        NO_MESSAGE,
        NO_TRANSPORT,
        SILENT_MODE,
        SLACK_REPLY,
        SEEN,
        ACTIVE,
        TURN_OPEN,
        ANSWER_DELAY,
        ANSWER_EXPIRED,
        INITIATIVE,
        QUIET_HOURS,
        DAILY_LIMIT,
        NOTIFICATION_SPACING,
    }

    record Decision(Message message, @Nullable Route route, Delivery delivery) {}

    /* A refusal with a retry time holds the item. A refusal without one settles the attempt. */
    record Message(Reason reason, @Nullable Instant retryAt) {
        boolean eligible() {
            return reason == Reason.ELIGIBLE;
        }
    }

    /*
     * A delivery time in the future requires a fresh evaluation at send. SLACK_REPLY is the answer
     * to a person's own message in Slack.
     */
    record Delivery(
            Reason reason,
            @Nullable Transport transport,
            @Nullable Instant at) {}

    static Decision decide(Facts facts) {
        boolean mayChooseRoute = facts.boundary() == Boundary.DISPATCH
                && !(facts.contact() instanceof Answer || facts.contact() instanceof RequestedResult);
        Route route = facts.routing().select(mayChooseRoute);
        if (route == null) {
            return new Decision(new Message(Reason.ROUTE_UNAVAILABLE, null), null, noDelivery(Reason.NO_MESSAGE));
        }
        Reason restriction = startedMessageRestriction(facts.controls());
        Message message = switch (facts.contact()) {
            case Answer _, RequestedResult _ -> new Message(Reason.ELIGIBLE, null);
            case Agreed agreed -> agreedMessage(facts, agreed, restriction);
            case Unprompted unprompted -> unpromptedMessage(facts, unprompted, restriction);
        };
        Delivery delivery = message.eligible() ? delivery(facts, route, restriction) : noDelivery(Reason.NO_MESSAGE);
        return new Decision(message, route, delivery);
    }

    private static Reason startedMessageRestriction(Controls controls) {
        if (!controls.instanceAllowsStartedMessages() || !controls.workspaceAllowsStartedMessages()) {
            return Reason.STARTED_MESSAGES_DISABLED;
        }
        if (controls.paused()) {
            return Reason.PAUSED;
        }
        return controls.muted() ? Reason.MUTED : Reason.ELIGIBLE;
    }

    private static Message agreedMessage(Facts facts, Agreed agreed, Reason restriction) {
        if (restriction != Reason.ELIGIBLE) {
            return new Message(restriction, null);
        }
        if (!agreed.inScope()) {
            return new Message(Reason.OUTSIDE_AGREEMENT, null);
        }
        Instant now = facts.now().toInstant();
        if (now.isBefore(agreed.dueAt())) {
            return new Message(Reason.BEFORE_SLOT, agreed.dueAt());
        }
        // ADR 0133 bounds the occurrence. A committed notification can wait past its grace.
        if (facts.boundary() != Boundary.SEND && !now.isBefore(agreed.dueAt().plus(CHECK_IN_GRACE))) {
            return new Message(Reason.GRACE_ENDED, null);
        }
        return new Message(Reason.ELIGIBLE, null);
    }

    private static Message unpromptedMessage(Facts facts, Unprompted contact, Reason restriction) {
        if (restriction != Reason.ELIGIBLE) {
            return new Message(restriction, null);
        }
        if (contact.heldResult()) {
            return new Message(Reason.STUDY_HOLD, null);
        }
        if (contact.duplicateProblemVersion()) {
            return new Message(Reason.DUPLICATE, null);
        }
        Trigger trigger = contact.trigger();
        if (trigger == Update.NONE || (trigger instanceof WithheldCopy copy && !copy.qualifies())) {
            return new Message(Reason.NO_TRIGGER, null);
        }
        if (trigger == Update.NEW_ADMITTED_PROBLEM && contact.qualification() != Qualification.CURRENT) {
            return new Message(Reason.UNQUALIFIED, null);
        }
        // The optional start gates hold inbox items at dispatch, before a turn starts.
        if (facts.boundary() == Boundary.DISPATCH) {
            Instant now = facts.now().toInstant();
            Instant lastMessage = contact.lastMessageWithIdleOnly();
            Instant idleAt = lastMessage == null ? now : lastMessage.plus(ACTIVITY_WINDOW);
            Instant activeAt = contact.nextActiveHoursStart();
            if (activeAt != null && activeAt.isAfter(now) && activeAt.isAfter(idleAt)) {
                return new Message(Reason.OUTSIDE_ACTIVE_HOURS, activeAt);
            }
            if (idleAt.isAfter(now)) {
                return new Message(Reason.IDLE_ONLY, idleAt);
            }
        }
        return new Message(Reason.ELIGIBLE, null);
    }

    private static Delivery delivery(Facts facts, Route route, Reason restriction) {
        Transport transport =
                route == Route.SLACK_DM ? Transport.SLACK_DM : facts.routing().appNotification();
        if (transport == null
                || (facts.contact() instanceof Answer && transport == Transport.EMAIL)
                || (transport == Transport.SLACK_NOTICE
                        && !(facts.contact() instanceof RequestedResult || facts.contact() instanceof Agreed))) {
            return noDelivery(Reason.NO_TRANSPORT);
        }
        if (facts.controls().silentMode()) {
            return noDelivery(Reason.SILENT_MODE);
        }
        Instant now = facts.now().toInstant();
        if (facts.contact() instanceof Answer && transport == Transport.SLACK_DM) {
            return new Delivery(Reason.SLACK_REPLY, transport, now);
        }
        if (facts.contact() instanceof RequestedResult && restriction != Reason.ELIGIBLE) {
            return noDelivery(restriction);
        }
        if (facts.attention().seen()) {
            return noDelivery(Reason.SEEN);
        }
        Instant foreground = facts.attention().foregroundConnectedAt();
        boolean pushWhileActive =
                transport == Transport.PUSH && foreground != null && foreground.isAfter(now.minus(ACTIVITY_WINDOW));
        if (pushWhileActive && !(facts.contact() instanceof Answer)) {
            return noDelivery(Reason.ACTIVE);
        }
        return switch (facts.contact()) {
            case Answer answer -> answerPush(answer, now, pushWhileActive);
            case RequestedResult _ -> new Delivery(Reason.ELIGIBLE, transport, now);
            case Agreed agreed -> {
                QuietHours quiet = facts.controls().quietHours();
                Instant end = quiet.endIfQuiet(facts.now());
                if (end != null) {
                    Instant slotQuietEnd =
                            quiet.endIfQuiet(agreed.dueAt().atZone(facts.now().getZone()));
                    if (!agreed.slotSetInsideQuietHours() || !end.equals(slotQuietEnd)) {
                        yield new Delivery(Reason.QUIET_HOURS, transport, end);
                    }
                }
                yield new Delivery(Reason.ELIGIBLE, transport, now);
            }
            case Unprompted _ -> unpromptedDelivery(facts, transport, now);
        };
    }

    private static Delivery answerPush(Answer answer, Instant now, boolean pushWhileActive) {
        Instant ended = answer.turnEndedAt();
        if (ended == null) {
            return noDelivery(Reason.TURN_OPEN);
        }
        Instant ready = ended.plus(ANSWER_DELAY);
        if (!now.isBefore(ready.plus(ANSWER_LIFETIME))) {
            return noDelivery(Reason.ANSWER_EXPIRED);
        }
        if (now.isBefore(ready)) {
            return new Delivery(Reason.ANSWER_DELAY, Transport.PUSH, ready);
        }
        return pushWhileActive ? noDelivery(Reason.ACTIVE) : new Delivery(Reason.ELIGIBLE, Transport.PUSH, now);
    }

    private static Delivery unpromptedDelivery(Facts facts, Transport transport, Instant now) {
        Initiative initiative = facts.controls().initiative();
        LocalDate lighterTouchUntil = facts.controls().lighterTouchUntil();
        if (initiative == Initiative.ONLY_WHEN_AGREED
                || (lighterTouchUntil != null && facts.now().toLocalDate().isBefore(lighterTouchUntil))) {
            return noDelivery(Reason.INITIATIVE);
        }
        Instant quietEnd = facts.controls().quietHours().endIfQuiet(facts.now());
        if (quietEnd != null) {
            return new Delivery(Reason.QUIET_HOURS, transport, quietEnd);
        }
        if (facts.budget().sentToday() >= initiative.dailyLimit) {
            return noDelivery(Reason.DAILY_LIMIT);
        }
        Instant last = facts.budget().lastSentAt();
        if (initiative == Initiative.OFTEN && last != null) {
            Instant ready = last.plus(OFTEN_SPACING);
            if (now.isBefore(ready)) {
                return new Delivery(Reason.NOTIFICATION_SPACING, transport, ready);
            }
        }
        return new Delivery(Reason.ELIGIBLE, transport, now);
    }

    private static Delivery noDelivery(Reason reason) {
        return new Delivery(reason, null, null);
    }
}
