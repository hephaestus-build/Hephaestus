package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Attention;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Boundary;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Budget;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Contact;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Controls;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Facts;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Initiative;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.QuietHours;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Route;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Routing;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceStartedMessages;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the current facts that {@link ContactPolicy} decides on, from the rows that hold them. Each boundary calls it
 * again, so a pause or a switch that changed since an earlier boundary holds at the next one.
 *
 * <p>It reads no message, read state or reply. Silence is never read as dislike (ADR 0139 decision 16), and
 * {@code ContactRulesReadNoMessagesTest} keeps it so. The contact is with the person whose account the caller names, so
 * the facts bind that account as the owner of the person rows they read.
 */
@Service
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@RequiredArgsConstructor
class ContactFacts {

    /**
     * The person's own contact controls until #4937 stores them: not paused and not muted (ADR 0139 decision 13),
     * quiet hours from 22:00 to 08:00 (decision 9) and the initiative level Sometimes (decision 4).
     */
    static final PersonControls PERSON_DEFAULTS =
            new PersonControls(false, false, QuietHours.DEFAULT, Initiative.SOMETIMES, null);

    record PersonControls(
            boolean paused,
            boolean muted,
            QuietHours quietHours,
            Initiative initiative,
            @Nullable LocalDate lighterTouchUntil) {}

    /** An active check-in that a contact can be agreed under. */
    record AgreedScope(CheckIn.Kind kind, @Nullable String topic, Route place) {}

    record MeterReading(LocalDate localDate, int count, Instant lastAt) {}

    /** What the caller knows about one contact at one boundary. */
    record Occasion(Contact contact, Boundary boundary, Routing routing, Attention attention) {}

    private final ContactProperties properties;
    private final WorkspaceStartedMessages workspaces;
    private final SilentModeQuery silentMode;
    private final AccountDirectory accounts;
    private final CheckInRepository checkIns;
    private final AccountMeterRepository meters;
    private final OwnerScope owners;

    /** The facts of one contact with one person in one workspace, at {@code now} in the person's time zone. */
    @Transactional(readOnly = true)
    public Facts facts(long workspaceId, long accountId, Occasion occasion, Instant now) {
        owners.ofJob(accountId);
        ZonedDateTime local = now.atZone(zone(accountId));
        return new Facts(
                occasion.contact(),
                occasion.boundary(),
                local,
                occasion.routing(),
                controls(workspaceId),
                occasion.attention(),
                budget(accountId, local.toLocalDate()));
    }

    /** The check-in that a contact is agreed under, while it is active and belongs to this person here. */
    @Transactional(readOnly = true)
    public Optional<AgreedScope> agreedScope(long workspaceId, long accountId, UUID checkInId) {
        owners.ofJob(accountId);
        return checkIns.findActiveScope(checkInId, workspaceId, accountId);
    }

    private Controls controls(long workspaceId) {
        PersonControls person = PERSON_DEFAULTS;
        return new Controls(
                properties.startedMessagesEnabled(),
                workspaces.startedMessagesEnabled(workspaceId),
                person.paused(),
                person.muted(),
                silentMode.isSilentModeEngaged(),
                person.quietHours(),
                person.initiative(),
                person.lighterTouchUntil());
    }

    private Budget budget(long accountId, LocalDate today) {
        return meters.findReading(accountId, AccountMeter.Meter.DAILY_UPDATES)
                .map(reading -> new Budget(reading.localDate().equals(today) ? reading.count() : 0, reading.lastAt()))
                .orElse(new Budget(0, null));
    }

    private ZoneId zone(long accountId) {
        return accounts.timeZone(accountId).orElse(AccountDirectory.DEFAULT_TIME_ZONE);
    }
}
