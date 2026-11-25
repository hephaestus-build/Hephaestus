package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Attention;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Boundary;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Facts;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Qualification;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Route;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Routing;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Transport;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Unprompted;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Update;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSettingsService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each fact of the contact policy comes from the row that holds it, read as the person it is about. */
class ContactFactsIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Unprompted UPDATE =
            new Unprompted(Update.NEW_ADMITTED_PROBLEM, Qualification.CURRENT, false, false, null, null);
    private static final Routing APP = new Routing(Set.of(Route.APP), null, Route.APP, Transport.PUSH);
    private static final Attention AWAY = new Attention(false, null);
    private static final ContactFacts.Occasion AT_COMMIT =
            new ContactFacts.Occasion(UPDATE, Boundary.COMMIT, APP, AWAY);

    @Autowired
    private ContactFacts contactFacts;

    @Autowired
    private WorkspaceSettingsService workspaceSettings;

    @Autowired
    private InstanceSettingsService instanceSettings;

    @Autowired
    private JdbcTemplate jdbc;

    /** Writes the fixture rows as the table owner, which the person row policies do not restrict. */
    private final JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();

    private Workspace workspace;
    private Workspace otherWorkspace;
    private long person;
    private long colleague;

    @BeforeEach
    void setUp() {
        User personUser = persistUser("contact-facts-person");
        workspace = createWorkspace("contact-facts", "Contact facts", "contact-facts", AccountType.ORG, personUser);
        otherWorkspace =
                createWorkspace("contact-facts-other", "Other", "contact-facts-other", AccountType.ORG, personUser);
        person = accountId(personUser);
        colleague = accountId(persistUser("contact-facts-colleague"));
        jdbc.update("UPDATE account SET time_zone = 'Europe/Berlin' WHERE id = ?", person);
    }

    @Test
    void shouldReadTheWorkspaceSwitchAndTheDefaultsOfAPersonWhoChoseNothing() {
        Facts facts = facts(Instant.parse("2026-11-25T12:00:00Z"));
        assertThat(facts.controls().workspaceAllowsStartedMessages()).isTrue();
        assertThat(facts.controls().instanceAllowsStartedMessages()).isTrue();
        assertThat(facts.controls().quietHours()).isEqualTo(ContactFacts.PERSON_DEFAULTS.quietHours());
        assertThat(facts.controls().initiative()).isEqualTo(ContactPolicy.Initiative.SOMETIMES);
        assertThat(facts.controls().paused()).isFalse();
        assertThat(facts.now().getZone().getId()).isEqualTo("Europe/Berlin");

        workspaceSettings.updateStartedMessages(workspace.getId(), false);

        assertThat(facts(Instant.parse("2026-11-25T12:00:00Z")).controls().workspaceAllowsStartedMessages())
                .isFalse();
    }

    @Test
    void shouldReadSilentModeAtEachCall() {
        setSilentMode(true);
        try {
            assertThat(facts(Instant.parse("2026-11-25T12:00:00Z")).controls().silentMode())
                    .isTrue();
        } finally {
            setSilentMode(false);
        }
        assertThat(facts(Instant.parse("2026-11-25T12:00:00Z")).controls().silentMode())
                .isFalse();
    }

    @Test
    void shouldCountOnlyTheUnpromptedNotificationsOfThePersonsLocalDate() {
        Instant lastSent = Instant.parse("2026-11-25T20:00:00Z");
        owner.update(
                "INSERT INTO account_meter (account_id, meter, local_date, count, last_at) VALUES (?, ?, ?, ?, ?)",
                person,
                "DAILY_UPDATES",
                LocalDate.parse("2026-11-25"),
                1,
                Timestamp.from(lastSent));

        // 23:30 in Berlin is still 25 November.
        var lateEvening = facts(Instant.parse("2026-11-25T22:30:00Z")).budget();
        assertThat(lateEvening.sentToday()).isEqualTo(1);
        assertThat(lateEvening.lastSentAt()).isEqualTo(lastSent);

        // 00:30 in Berlin is 26 November: nothing was sent today, and the last send still spaces the next one.
        var afterMidnight = facts(Instant.parse("2026-11-25T23:30:00Z")).budget();
        assertThat(afterMidnight.sentToday()).isZero();
        assertThat(afterMidnight.lastSentAt()).isEqualTo(lastSent);

        // A colleague's count is not this person's.
        var colleagueBudget = contactFacts
                .facts(workspace.getId(), colleague, AT_COMMIT, Instant.parse("2026-11-25T22:30:00Z"))
                .budget();
        assertThat(colleagueBudget.sentToday()).isZero();
        assertThat(colleagueBudget.lastSentAt()).isNull();
    }

    @Test
    void shouldAgreeOnlyUnderAnActiveCheckInOfThePersonInThatWorkspace() {
        UUID active = checkIn(workspace, person, "GOAL_FOLLOW_UP", "goal-7", "SLACK_DM", "ACTIVE");
        UUID paused = checkIn(workspace, person, "CHECK_IN", null, "APP", "PAUSED");
        UUID colleagues = checkIn(workspace, colleague, "CHECK_IN", null, "APP", "ACTIVE");
        UUID elsewhere = checkIn(otherWorkspace, person, "CHECK_IN", null, "APP", "ACTIVE");

        assertThat(contactFacts.agreedScope(workspace.getId(), person, active))
                .contains(new ContactFacts.AgreedScope(CheckIn.Kind.GOAL_FOLLOW_UP, "goal-7", Route.SLACK_DM));
        assertThat(contactFacts.agreedScope(workspace.getId(), person, paused)).isEmpty();
        assertThat(contactFacts.agreedScope(workspace.getId(), person, colleagues))
                .isEmpty();
        assertThat(contactFacts.agreedScope(workspace.getId(), person, elsewhere))
                .isEmpty();
    }

    private Facts facts(Instant now) {
        return contactFacts.facts(workspace.getId(), person, AT_COMMIT, now);
    }

    private UUID checkIn(Workspace in, long account, String kind, @Nullable String topic, String place, String state) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO check_in (id, workspace_id, account_id, kind, topic, place, state, created_at,"
                        + " state_changed_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                id,
                in.getId(),
                account,
                kind,
                topic,
                place,
                state);
        return id;
    }

    /** Another class in the database may leave Silent Mode on. */
    private void setSilentMode(boolean engaged) {
        var current = instanceSettings.get();
        if (current.isSilentModeEngaged() != engaged) {
            instanceSettings.updateSilentMode(
                    engaged,
                    engaged ? "contact facts test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }
}
