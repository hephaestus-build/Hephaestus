package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery.GrantStanding;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Widening needs a new decision; removing surveys preserves decisions that cover the retained scope. */
class ConsentRewordingIntegrationTest extends BaseIntegrationTest {

    private static final String NARROWER_WORDING = "2026-09-11";
    private static final String WIDER_WORDING = "2026-10-04";

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
        return inTransaction(() -> service.completeFirstLogin(accountId, request));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> work.get()));
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepAnExistingDecisionWithoutAskingAgainWhenTheWordingNarrows(boolean granted) {
        long id = recordWiderConsent(granted);
        long decisionId = latestResearchId(id);

        assertThat(service.hasCompletedCurrentNotice(id)).isTrue();
        assertThat(service.status(id).completed()).isTrue();
        assertThat(service.status(id).participateInResearch()).isEqualTo(granted);
        assertThat(service.participates(id)).isEqualTo(granted);
        Map<Long, Long> expected = granted ? Map.of(id, decisionId) : Map.of();
        assertThat(inTransaction(() -> service.currentGrants(List.of(id)))).isEqualTo(expected);
        assertThat(inTransaction(() -> service.lockCurrentGrants(List.of(id)))).isEqualTo(expected);

        complete(id, new ConsentService.FirstLoginConsentDTO(ConsentService.WORDING_VERSION, true, granted, "AET"));

        assertThat(latestResearchId(id)).isEqualTo(decisionId);
        assertThat(versionsOf(id)).containsExactlyInAnyOrder(WIDER_WORDING, WIDER_WORDING, WIDER_WORDING);
    }

    @Test
    void shouldRecordWithdrawalAndANewGrantWithCurrentWordingWithoutRevivingTheOldGrant() {
        long id = recordWiderConsent(true);
        long originalGrant = latestResearchId(id);
        assertThat(inTransaction(() -> service.grantStandings(Map.of(id, originalGrant))))
                .containsEntry(id, GrantStanding.CURRENT);

        inTransaction(() -> service.setResearchParticipation(
                id, new ConsentService.ResearchConsentDTO(ConsentService.WORDING_VERSION, false, "AET")));

        assertThat(service.participates(id)).isFalse();
        assertThat(inTransaction(() -> service.currentGrants(List.of(id)))).isEmpty();
        assertThat(inTransaction(() -> service.grantStandings(Map.of(id, originalGrant))))
                .containsEntry(id, GrantStanding.WITHDRAWN);

        inTransaction(() -> service.setResearchParticipation(
                id, new ConsentService.ResearchConsentDTO(ConsentService.WORDING_VERSION, true, "AET")));

        long newGrant = latestResearchId(id);
        assertThat(newGrant).isNotEqualTo(originalGrant);
        assertThat(inTransaction(() -> service.currentGrants(List.of(id))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(id, newGrant));
        assertThat(inTransaction(() -> service.grantStandings(Map.of(id, originalGrant))))
                .containsEntry(id, GrantStanding.WITHDRAWN);
        assertThat(versionsOf(id))
                .containsExactlyInAnyOrder(
                        WIDER_WORDING,
                        WIDER_WORDING,
                        WIDER_WORDING,
                        ConsentService.WORDING_VERSION,
                        ConsentService.WORDING_VERSION);
    }

    private long recordWiderConsent(boolean granted) {
        Account account = accounts.save(new Account("Participant before survey removal"));
        for (ConsentDecision.Purpose purpose : ConsentDecision.Purpose.values()) {
            decisions.save(new ConsentDecision(
                    account,
                    purpose,
                    purpose != ConsentDecision.Purpose.RESEARCH_PARTICIPATION || granted,
                    ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                    WIDER_WORDING,
                    purpose == ConsentDecision.Purpose.RESEARCH_PARTICIPATION ? "AET" : null));
        }
        return Objects.requireNonNull(account.getId());
    }

    private long latestResearchId(long accountId) {
        return Objects.requireNonNull(decisions
                .findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION)
                .orElseThrow()
                .getId());
    }

    private List<@Nullable String> versionsOf(long accountId) {
        return jdbc.queryForList(
                "SELECT notice_version FROM consent_decision WHERE account_id = ?", String.class, accountId);
    }
}
