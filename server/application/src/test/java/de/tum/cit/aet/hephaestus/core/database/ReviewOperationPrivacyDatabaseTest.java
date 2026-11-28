package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonFeedbackAnswerRedaction;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.adapter.PracticesPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.advice.AdviceRedaction;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class ReviewOperationPrivacyDatabaseTest {
    @Test
    void shouldExportOperationMetadataAndEraseOnlyTheSelectedActorsReferences() {
        var database = NativePracticeFixture.resultParent("prod");
        var source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            INSERT INTO account(id,display_name,status) VALUES(979200,'Other operator','ACTIVE');
            UPDATE review SET requested_by_id=978003,checks='{"private":"Other developer words"}'::jsonb
            WHERE id='00000000-0000-0000-0000-000000978011';
            INSERT INTO review_batch(id,workspace_id,kind,from_at,to_at,requested_by_account_id,confirmed_by_account_id)
            VALUES('00000000-0000-0000-0000-000000979201',978001,'scm.pull_request',now()-interval '1 day',now(),978003,979200),
                  ('00000000-0000-0000-0000-000000979202',978001,'scm.pull_request',now()-interval '1 day',now(),979200,978003),
                  ('00000000-0000-0000-0000-000000979203',978001,'scm.pull_request',now()-interval '1 day',now(),979200,979200);
            INSERT INTO review_schedule(id,workspace_id,kind,lookback_days,next_run_at,created_by_account_id)
            VALUES('00000000-0000-0000-0000-000000979204',978001,'scm.pull_request',1,now(),978003),
                  ('00000000-0000-0000-0000-000000979205',978001,'scm.issue',1,now(),979200);
            """);
        var scope = new PersonScope(978003L, List.of(), List.of());
        var catalog = new PracticesPersonDataCatalog(
                new NamedParameterJdbcTemplate(source),
                JsonMapper.builder().build(),
                mock(FeedbackCopyCleanupCustody.class),
                mock(PersonFeedbackAnswerRedaction.class),
                mock(PersonIdentityResolver.class),
                mock(ScanReportService.class),
                mock(AdviceRedaction.class));
        var stores = catalog.contributors().stream()
                .filter(store ->
                        Set.of("review", "review_batch", "review_schedule").contains(store.store()))
                .toList();
        assertThat(stores)
                .extracting(store -> store.store())
                .containsExactlyInAnyOrder("review", "review_batch", "review_schedule");
        for (var store : stores) {
            var selection = store.select(scope);
            assertThat(store.export(selection, scope))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.toString()).doesNotContain("Other developer words"));
            assertThat(store.erase(selection, scope)).isEqualTo(selection.rows().size());
        }
        assertThat(jdbc.queryForObject(
                        "SELECT requested_by_id IS NULL FROM review WHERE id='00000000-0000-0000-0000-000000978011'",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForList("""
            SELECT COALESCE(requested_by_account_id::text,'null')||'|'||COALESCE(confirmed_by_account_id::text,'null')
              FROM review_batch WHERE id IN ('00000000-0000-0000-0000-000000979201','00000000-0000-0000-0000-000000979202','00000000-0000-0000-0000-000000979203') ORDER BY id
            """, String.class)).containsExactly("null|979200", "979200|null", "979200|979200");
        assertThat(jdbc.queryForList("""
            SELECT COALESCE(created_by_account_id::text,'null')||'|'||enabled
            FROM review_schedule WHERE id IN ('00000000-0000-0000-0000-000000979204','00000000-0000-0000-0000-000000979205') ORDER BY id
            """, String.class)).containsExactly("null|true", "979200|true");
        assertThat(jdbc.queryForObject(
                        "SELECT checks->>'private' FROM review WHERE id='00000000-0000-0000-0000-000000978011'",
                        String.class))
                .isEqualTo("Other developer words");
    }
}
