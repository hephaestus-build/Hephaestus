package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A new wording version reaches every account, once, against the real ledger and its SQL. */
class ConsentRewordingIntegrationTest extends BaseIntegrationTest {

    private static final String NARROWER_WORDING = "2026-09-11";

    @Autowired
    private ConsentDecisionRepository decisions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ConsentService service;

    // Built here rather than configured, so the test shares the cached application context.
    @BeforeEach
    void setUp() {
        service = new ConsentService(decisions, accounts, new ConsentProperties("AET"));
    }

    // The service is not a bean here, so the transaction its Spring proxy would open is opened by hand.
    private ConsentService.ConsentStatusDTO complete(long accountId, ConsentService.FirstLoginConsentDTO request) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager)
                .execute(status -> service.completeFirstLogin(accountId, request)));
    }

    @Test
    void shouldAskAnAccountOnNarrowerWordingExactlyOnceMore() {
        Account account = accounts.save(new Account("Rewording participant"));
        long id = Objects.requireNonNull(account.getId());
        for (ConsentDecision.Purpose purpose : ConsentDecision.Purpose.values()) {
            decisions.save(new ConsentDecision(
                    account,
                    purpose,
                    true,
                    ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                    NARROWER_WORDING,
                    purpose == ConsentDecision.Purpose.RESEARCH_PARTICIPATION ? "AET" : null));
        }

        var before = service.status(id);
        assertThat(before.completed()).isFalse();
        assertThat(before.participateInResearch()).isFalse();

        var request = new ConsentService.FirstLoginConsentDTO(ConsentService.WORDING_VERSION, true, true, "AET");
        var after = complete(id, request);
        assertThat(after.completed()).isTrue();
        assertThat(after.participateInResearch()).isTrue();

        complete(id, request);
        assertThat(service.status(id).completed()).isTrue();
        assertThat(versionsOf(id))
                .as("three decisions on the narrower wording stay as history, three more are added once")
                .containsExactlyInAnyOrder(
                        NARROWER_WORDING,
                        NARROWER_WORDING,
                        NARROWER_WORDING,
                        ConsentService.WORDING_VERSION,
                        ConsentService.WORDING_VERSION,
                        ConsentService.WORDING_VERSION);
    }

    private List<@Nullable String> versionsOf(long accountId) {
        return jdbc.queryForList(
                "SELECT notice_version FROM consent_decision WHERE account_id = ?", String.class, accountId);
    }
}
