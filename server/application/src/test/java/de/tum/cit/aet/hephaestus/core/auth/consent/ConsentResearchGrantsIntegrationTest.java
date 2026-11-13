package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery.GrantStanding;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cohort reads answer exactly what {@link ConsentService#participates} answers one account at a
 * time, against the real ledger and its ordering, and an old grant's standing remembers a withdrawal
 * even after the account says yes again.
 */
class ConsentResearchGrantsIntegrationTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private ConsentDecisionRepository decisions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ConsentService service;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        service = new ConsentService(decisions, accounts, new ConsentProperties("AET"));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private long account(String name) {
        return Objects.requireNonNull(accounts.save(new Account(name)).getId());
    }

    private long decide(long accountId, boolean granted, String notice, String organization, Instant at) {
        jdbc.update(
                "INSERT INTO consent_decision(account_id,purpose,granted,mechanism,notice_version,research_organization,occurred_at)"
                        + " VALUES (?,'RESEARCH_PARTICIPATION',?,'ACCOUNT_SETTINGS',?,?,?)",
                accountId,
                granted,
                notice,
                organization,
                Timestamp.from(at));
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT max(id) FROM consent_decision WHERE account_id = ?", Long.class, accountId));
    }

    private long decide(long accountId, boolean granted, Instant at) {
        return decide(accountId, granted, ConsentService.WORDING_VERSION, "AET", at);
    }

    @Test
    void shouldReadTheSameAuthorisingGrantForACohortAsForEachAccount() {
        long granted = account("granted");
        long grant = decide(granted, true, T0);
        long refused = account("refused");
        decide(refused, false, T0);
        long withdrawn = account("withdrawn");
        decide(withdrawn, true, T0);
        decide(withdrawn, false, T0.plusSeconds(60));
        long regranted = account("regranted");
        decide(regranted, true, T0);
        decide(regranted, false, T0.plusSeconds(60));
        long regrant = decide(regranted, true, T0.plusSeconds(120));
        long oldWording = account("old wording");
        decide(oldWording, true, "2026-09-11", "AET", T0);
        long otherOrganisation = account("other organisation");
        decide(otherOrganisation, true, ConsentService.WORDING_VERSION, "Other", T0);
        long tie = account("tie");
        decide(tie, true, T0);
        decide(tie, false, T0); // same instant: the higher id is the latest, as findFirst orders it
        long none = account("never asked, legacy preference only");
        long suspended = account("suspended");
        decide(suspended, true, T0);
        long deleting = account("deleting");
        decide(deleting, true, T0);
        setStatus(suspended, Account.Status.BLOCKED);
        setStatus(deleting, Account.Status.DELETING);
        List<Long> all = List.of(
                granted, refused, withdrawn, regranted, oldWording, otherOrganisation, tie, none, suspended, deleting);

        Map<Long, Long> grants = inTransaction(() -> service.currentGrants(all));
        Map<Long, Long> locked = inTransaction(() -> service.lockCurrentGrants(all));

        assertThat(grants).isEqualTo(Map.of(granted, grant, regranted, regrant));
        assertThat(locked).isEqualTo(grants);
        for (long id : all) {
            boolean active = accounts.findById(id).orElseThrow().getStatus() == Account.Status.ACTIVE;
            assertThat(grants.containsKey(id))
                    .as("cohort read agrees with participates() for account %s", id)
                    .isEqualTo(service.participates(id) && active);
        }
        assertThat(inTransaction(
                        () -> new ConsentService(decisions, accounts, new ConsentProperties(null)).currentGrants(all)))
                .as("no study configured authorises nobody")
                .isEmpty();
    }

    @Test
    void shouldKeepAWithdrawalOwedAfterTheAccountSaysYesAgain() {
        long regranted = account("regranted");
        long first = decide(regranted, true, T0);
        decide(regranted, false, T0.plusSeconds(60));
        long second = decide(regranted, true, T0.plusSeconds(120));
        long superseded = account("asked again under a new organisation");
        long original = decide(superseded, true, T0);
        decide(superseded, true, ConsentService.WORDING_VERSION, "Other", T0.plusSeconds(60));
        long current = account("current");
        long currentGrant = decide(current, true, T0);
        long erased = account("erased");
        long erasedGrant = decide(erased, true, T0);
        jdbc.update("UPDATE consent_decision SET account_id = NULL WHERE account_id = ?", erased);
        setStatus(erased, Account.Status.DELETED);

        assertThat(inTransaction(() -> service.grantStandings(
                        Map.of(regranted, first, superseded, original, current, currentGrant, erased, erasedGrant))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        regranted, GrantStanding.WITHDRAWN,
                        superseded, GrantStanding.SUPERSEDED,
                        current, GrantStanding.CURRENT,
                        erased, GrantStanding.ACCOUNT_ERASED));
        assertThat(inTransaction(() -> service.grantStandings(Map.of(regranted, second))))
                .containsEntry(regranted, GrantStanding.CURRENT);
        assertThat(inTransaction(() -> new ConsentService(decisions, accounts, new ConsentProperties("Renamed"))
                        .grantStandings(Map.of(current, currentGrant))))
                .containsEntry(current, GrantStanding.QUESTION_CHANGED);
    }

    private void setStatus(long id, Account.Status status) {
        Account account = accounts.findById(id).orElseThrow();
        AccountFixtures.moveTo(account, status);
        accounts.save(account);
    }
}
