package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.adapter.PracticesPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class AgentPersonDataCatalogDatabaseTest {
    @Test
    void shouldExportAndEraseDeveloperJobsAndProductRowsAgainstTheCutoverSchema() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");
        var jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        var plain = jdbc.getJdbcTemplate();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var lifecycle = mock(ReviewJobLifecycleService.class);
        var noAcknowledgement = new AgentPersonDataCatalog(jdbc, mapper, List.of(), lifecycle, mock(AgentRuns.class));
        var person = new PersonScope(992106L, List.of(), List.of(992103L));
        var job = UUID.fromString("00000000-0000-0000-0000-000000992111");
        plain.update("UPDATE review_job SET capture='{}'::jsonb WHERE id=?", job);
        var unacknowledgedStore = noAcknowledgement.contributors().stream()
                .filter(store -> store.store().equals("review_job"))
                .findFirst()
                .orElseThrow();
        var selected = unacknowledgedStore.select(person);
        assertThat(selected.rows())
                .extracting(row -> row.columns().get("id"))
                .contains(job.toString())
                .doesNotContain("00000000-0000-0000-0000-000000992112");
        assertThat(unacknowledgedStore.export(selected, person))
                .anySatisfy(row -> assertThat(row.path("id").asString()).isEqualTo(job.toString()));
        assertThatThrownBy(() -> unacknowledgedStore.prepareErasure(selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Evidence-copy erasure acknowledgement is unavailable");
        assertThat(plain.queryForObject("SELECT id FROM review_job WHERE id=?", UUID.class, job))
                .isEqualTo(job);

        var runs = mock(AgentRuns.class);
        var runtime =
                new AgentPersonDataCatalog(jdbc, mapper, List.of(mock(PersonEvidenceErasure.class)), lifecycle, runs);
        var run = plain.queryForObject("SELECT run_id FROM review_job WHERE id=?", UUID.class, job);
        var products = new PracticesPersonDataCatalog(
                jdbc,
                mapper,
                mock(FeedbackCopyCleanupCustody.class),
                mock(PersonIdentityResolver.class),
                mock(ScanReportService.class));
        var stores = new ArrayList<PersonDataContributor>(runtime.contributors());
        stores.addAll(products.contributors());
        var frozen = stores.stream()
                .map(store -> new Selected(store, store.select(person)))
                .toList();
        frozen.forEach(entry -> entry.store().export(entry.selection(), person));
        frozen.forEach(entry -> entry.store().prepareErasure(entry.selection()));
        var transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(plain.getDataSource())));
        transactions.executeWithoutResult(transaction -> frozen.stream()
                .sorted(Comparator.comparingInt(entry -> entry.store().getOrder()))
                .forEach(entry -> entry.store().erase(entry.selection(), person)));
        assertThat(plain.queryForList("SELECT id FROM review_job WHERE id=?", UUID.class, job))
                .isEmpty();
        verify(runs).forget(eq(992101L), argThat(forgotten -> forgotten.contains(Objects.requireNonNull(run))));
        assertThat(plain.queryForList(
                        "SELECT id FROM result WHERE id=?",
                        UUID.class,
                        UUID.fromString("00000000-0000-0000-0000-000000992141")))
                .isEmpty();
        assertThat(plain.queryForList(
                        "SELECT id FROM feedback WHERE id=?",
                        UUID.class,
                        UUID.fromString("00000000-0000-0000-0000-000000992161")))
                .isEmpty();
        assertThat(plain.queryForList(
                        "SELECT id FROM review_job WHERE id=?",
                        UUID.class,
                        UUID.fromString("00000000-0000-0000-0000-000000992112")))
                .containsExactly(UUID.fromString("00000000-0000-0000-0000-000000992112"));
        var copying = runtime.contributors().stream()
                .filter(store -> store.store().equals("review_job"))
                .findFirst()
                .orElseThrow()
                .select(new PersonScope(null, List.of(), List.of(), List.of(), List.of(), List.of(992109L)));
        assertThat(copying.rows())
                .extracting(row -> row.columns().get("id"))
                .contains("00000000-0000-0000-0000-000000992112");
    }

    @Test
    void shouldExportAndDetachInstanceDraftCallsIncludingOneOpenedAfterThePreview() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");
        var jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        var plain = jdbc.getJdbcTemplate();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var catalog = new AgentPersonDataCatalog(
                jdbc, mapper, List.of(), mock(ReviewJobLifecycleService.class), mock(AgentRuns.class));
        UUID report = UUID.randomUUID();
        plain.update("""
            INSERT INTO product_feedback(id,account_id,kind,message,submission_minute,created_at)
            VALUES (?,992106,'BUG','Synthetic report',clock_timestamp(),clock_timestamp())
            """, report);
        var person = new PersonScope(992106L, List.of(), List.of());
        var ledger = catalog.contributors().stream()
                .filter(store -> store.store().equals("llm_usage_event"))
                .findFirst()
                .orElseThrow();
        var preview = ledger.select(person);
        assertThat(preview.rows())
                .anySatisfy(row -> assertThat(row.columns()).containsEntry("source_id", report.toString()));
        UUID call = UUID.randomUUID();
        plain.update("""
            INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,funding_source,
                action_id,settle_after,initial_pricing_state,pricing_state,call_state,occurred_at,started_at)
            VALUES (?,'REPORT_DRAFT','PRODUCT_FEEDBACK_DRAFT',?,1,'CHAT','INSTANCE',gen_random_uuid(),clock_timestamp()+interval '2 minutes','UNPRICED','UNPRICED','OPEN',
                clock_timestamp(),clock_timestamp())
            """, call, report);
        assertThat(ledger.export(preview, person)).anySatisfy(row -> {
            assertThat(row.path("source_id").asString()).isEqualTo(report.toString());
            assertThat(row.path("calls").get(0).path("id").asString()).isEqualTo(call.toString());
        });
        ledger.erase(preview, person);
        assertThat(plain.queryForObject("SELECT call_state FROM llm_usage_event WHERE id=?", String.class, call))
                .isEqualTo("SETTLED");
        assertThat(plain.queryForObject(
                        "SELECT measurement_verification FROM llm_usage_event WHERE id=?", String.class, call))
                .isEqualTo("UNVERIFIABLE");
        assertThat(plain.queryForObject("SELECT unreported_calls FROM llm_usage_event WHERE id=?", Integer.class, call))
                .isEqualTo(1);
        assertThat(plain.queryForObject("SELECT source_id FROM llm_usage_event WHERE id=?", UUID.class, call))
                .isNotEqualTo(report);
        plain.update("UPDATE llm_usage_event SET call_state='ENDED',input_tokens=11 WHERE id=?", call);
        assertThat(plain.queryForObject("SELECT input_tokens FROM llm_usage_event WHERE id=?", Long.class, call))
                .isEqualTo(11);
    }

    private record Selected(PersonDataContributor store, PersonDataSelection selection) {}
}
