package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** A new wording version reaches every account, once, against the real ledger and its SQL. */
@TestPropertySource(properties = "hephaestus.consent.research-organization=AET")
class ConsentRewordingIntegrationTest extends BaseIntegrationTest {

    private static final String NARROWER_WORDING = "2026-09-11";

    @Autowired
    private ConsentService service;

    @Autowired
    private ConsentDecisionRepository decisions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

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
        var after = service.completeFirstLogin(id, request);
        assertThat(after.completed()).isTrue();
        assertThat(after.participateInResearch()).isTrue();

        service.completeFirstLogin(id, request);
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
