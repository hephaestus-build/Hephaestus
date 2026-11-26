package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.adapter.AgentPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.auth.AccountDirectoryService;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The personal purse pays for conversations of accounts with no workspace. Its rows carry the payer, sum by UTC month
 * for the shared cap and for each account, survive retention for the month they count in, and lose their payer on
 * erasure while their cost stays in the purse. Each test uses its own month, so rows of other tests never count.
 */
@Tag("integration")
class PersonalPurseLedgerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private LlmUsageEventRepository ledger;

    @Autowired
    private AgentPersonDataCatalog catalog;

    @Autowired
    private AccountDirectoryService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSumOneMonthOfThePersonalPurseForItsSharedCapAndForEachAccount() {
        YearMonth month = YearMonth.of(2031, 3);
        long ada = accountId(persistUser("purse-ada"));
        long bob = accountId(persistUser("purse-bob"));
        personal(ada, "ENDED", "PRICED", "1.00", null, at(month, 2));
        personal(ada, "OPEN", "UNPRICED", null, "0.50", at(month, 3));
        personal(bob, "ENDED", "PRICED", "2.00", null, at(month, 4));
        personal(ada, "ENDED", "PRICED", "5.00", null, at(month.minusMonths(1), 20));
        instanceProbe("3.00", at(month, 5));

        var spend = ledger.personalSpend(ada, start(month), start(month.plusMonths(1)));

        assertThat(spend.getShared()).isEqualByComparingTo("3.50");
        assertThat(spend.getAccount()).isEqualByComparingTo("1.50");
        assertThat(spend.getUnknown()).isFalse();
    }

    @Test
    void shouldFlagAPersonalCallWhoseCostNobodyKnows() {
        YearMonth month = YearMonth.of(2031, 4);
        long ada = accountId(persistUser("purse-unknown"));
        personal(ada, "ENDED", "UNPRICED", null, null, at(month, 2));

        var spend = ledger.personalSpend(ada, start(month), start(month.plusMonths(1)));

        assertThat(spend.getUnknown()).isTrue();
        assertThat(spend.getShared()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldRefuseAPersonalRowWithAWorkspaceAndAPayerOnAnotherSource() {
        long ada = accountId(persistUser("purse-scope"));
        long workspace = createWorkspace(
                        "purse-scope", "Purse", "purse-org", AccountType.ORG, persistUser("purse-owner"))
                .getId();

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO llm_usage_event(id,job_type,source_type,source_id,model_kind,funding_source,"
                                + "workspace_id,payer_account_id,call_state,initial_pricing_state,pricing_state,"
                                + "occurred_at,started_at) VALUES (gen_random_uuid(),'PERSONAL_CONVERSATION',"
                                + "'PERSONAL_CONVERSATION',gen_random_uuid(),'CHAT','INSTANCE',?,?,'ENDED','PRICED',"
                                + "'PRICED',now(),now())",
                        workspace,
                        ada))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,"
                                + "funding_source,payer_account_id,settle_after,call_state,initial_pricing_state,"
                                + "pricing_state,occurred_at,started_at) VALUES (gen_random_uuid(),'PROBE','PROBE',"
                                + "gen_random_uuid(),0,'CHAT','INSTANCE',?,now(),'ENDED','UNPRICED','UNPRICED',"
                                + "now(),now())",
                        ada))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO llm_usage_event(id,job_type,source_type,source_id,model_kind,funding_source,"
                                + "payer_account_id,call_state,initial_pricing_state,pricing_state,cost_bound_usd,"
                                + "occurred_at,started_at) VALUES (gen_random_uuid(),'PERSONAL_CONVERSATION',"
                                + "'PERSONAL_CONVERSATION',gen_random_uuid(),'CHAT','INSTANCE',?,'OPEN','PRICED',"
                                + "'UNPRICED',1,now(),now())",
                        ada))
                .as("an open personal call needs its attempt's epoch and a settling deadline")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepPersonalRowsOfThisAndThePreviousMonthThroughRetention() {
        long ada = accountId(persistUser("purse-retention"));
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        Instant lastMonth = start(now.minusMonths(1)).plusSeconds(3600);
        UUID kept = personal(ada, "ENDED", "PRICED", "1.00", null, lastMonth);
        UUID expired = personal(ada, "ENDED", "PRICED", "1.00", null, start(now.minusMonths(3)));
        UUID probe = instanceProbe("1.00", start(now.minusMonths(3)));

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> ledger.deleteExpired(lastMonth.plusSeconds(3600), 10_000));

        assertThat(exists(kept)).as("the purse still counts it").isTrue();
        assertThat(exists(expired)).isFalse();
        assertThat(exists(probe)).isFalse();
    }

    @Test
    void shouldDetachAnErasedAccountsPersonalCallsAndKeepTheirCostInThePurse() {
        YearMonth month = YearMonth.of(2031, 5);
        long ada = accountId(persistUser("purse-erased"));
        long bob = accountId(persistUser("purse-kept"));
        UUID ended = personal(ada, "ENDED", "PRICED", "1.00", null, at(month, 2));
        UUID open = personal(ada, "OPEN", "UNPRICED", null, "0.50", at(month, 3));
        UUID other = personal(bob, "ENDED", "PRICED", "2.00", null, at(month, 4));
        UUID source = UUID.fromString(column(ended, "source_id"));
        var before = ledger.personalSpend(ada, start(month), start(month.plusMonths(1)));
        var scope = new PersonScope(ada, List.of(), List.of());
        PersonDataContributor store = catalog.contributors().stream()
                .filter(contributor -> contributor.store().equals("llm_usage_event"))
                .findFirst()
                .orElseThrow();

        var selection = store.select(scope);
        assertThat(store.export(selection, scope).toString()).contains(ended.toString(), open.toString());
        store.erase(selection, scope);

        assertThat(column(ended, "payer_account_id")).isNull();
        assertThat(column(open, "payer_account_id")).isNull();
        assertThat(column(ended, "source_id")).isNotEqualTo(source.toString());
        assertThat(column(open, "call_state")).isEqualTo("SETTLED");
        assertThat(column(other, "payer_account_id")).isEqualTo(Long.toString(bob));
        var after = ledger.personalSpend(ada, start(month), start(month.plusMonths(1)));
        assertThat(after.getShared()).as("the open call counts at its bound").isEqualByComparingTo(before.getShared());
        assertThat(after.getAccount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldClearThePersonalConfirmationWhenTheChoiceChangesWithoutOne() {
        long ada = accountId(persistUser("purse-scope-cleared"));
        // Only an active account chooses how Hephaestus may use AI.
        jdbc.update("UPDATE account SET status = 'ACTIVE' WHERE id = ?", ada);
        accounts.setAiChoice(ada, AiChoice.CLOUD);
        jdbc.update("UPDATE account SET ai_personal_scope_at = now() WHERE id = ?", ada);

        accounts.setAiChoice(ada, AiChoice.IN_HOUSE_ONLY);

        assertThat(jdbc.queryForObject(
                        "SELECT ai_personal_scope_at IS NULL FROM account WHERE id = ?", Boolean.class, ada))
                .isTrue();
    }

    private UUID personal(
            long payer,
            String callState,
            String pricing,
            @Nullable String cost,
            @Nullable String bound,
            Instant occurredAt) {
        UUID id = UUID.randomUUID();
        boolean open = callState.equals("OPEN");
        jdbc.update(
                "INSERT INTO llm_usage_event(id,job_type,source_type,source_id,model_kind,funding_source,"
                        + "payer_account_id,call_state,lease_epoch,settle_after,initial_pricing_state,pricing_state,"
                        + "cost_usd,cost_bound_usd,occurred_at,started_at) VALUES (?,'PERSONAL_CONVERSATION',"
                        + "'PERSONAL_CONVERSATION',gen_random_uuid(),'CHAT','INSTANCE',?,?,?,?,'PRICED',?,"
                        + "CAST(? AS numeric),CAST(? AS numeric),?,?)",
                id,
                payer,
                callState,
                open ? 1 : null,
                open ? Timestamp.from(occurredAt.plusSeconds(300)) : null,
                pricing,
                cost,
                bound,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        return id;
    }

    private UUID instanceProbe(String cost, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,"
                        + "funding_source,settle_after,call_state,initial_pricing_state,pricing_state,cost_usd,"
                        + "occurred_at,started_at) VALUES (?,'PROBE','PROBE',gen_random_uuid(),0,'CHAT','INSTANCE',?,"
                        + "'ENDED','PRICED','PRICED',CAST(? AS numeric),?,?)",
                id,
                Timestamp.from(occurredAt),
                cost,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM llm_usage_event WHERE id = ?)", Boolean.class, id));
    }

    private @Nullable String column(UUID id, String column) {
        return jdbc.queryForObject(
                "SELECT CAST(" + column + " AS text) FROM llm_usage_event WHERE id = ?", String.class, id);
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static Instant at(YearMonth month, int day) {
        return month.atDay(day).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }
}
