package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1ScanMembershipDatabaseTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepScanMembershipAndReuseIndependentOfJobRetention(String context) throws Exception {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, """
            BEGIN;
            INSERT INTO item(id,workspace_id,kind,source_id) VALUES(978008,978001,'scm.repository',42);
            INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
            SELECT 999831,p.workspace_id,p.id,2,jsonb_set(v.definition,'{scan}',
                '{"itemKind":"scm.file","include":["**/*"],"exclude":[],"context":[],"reads":{"contract":"3.0.0","sources":[{"source":"scm.repository.tree","need":"COMPLETE"}]}}')
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=978004;
            UPDATE practice SET current_version_id=999831 WHERE id=978004;
            INSERT INTO item(id,workspace_id,kind,parent_id,path)
            SELECT files.n,978001,'scm.file',id,files.path FROM item CROSS JOIN (VALUES(999841,'a.ts'),(999842,'b.ts')) files(n,path)
            WHERE workspace_id=978001 AND kind='scm.repository' AND source_id=42;
            INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,budget_usd,ordered_at)
            SELECT '00000000-0000-0000-0000-000000999811',978001,id,'SCAN','repository-head','REQUEST','QUEUED',0.125,now()
            FROM item WHERE workspace_id=978001 AND kind='scm.repository' AND source_id=42;
            INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,items_listed,listing_complete)
            VALUES('00000000-0000-0000-0000-000000999811',978001,978004,999831,'READY',2,true);
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
            VALUES('00000000-0000-0000-0000-000000999811',978001,978004,999841,'file-a-head'),
                ('00000000-0000-0000-0000-000000999811',978001,978004,999842,'file-b-head');
            INSERT INTO review_job(id,review_id,workspace_id,status,processing_status,input,config_snapshot,trace_id,submitted_at)
            VALUES('00000000-0000-0000-0000-000000999821','00000000-0000-0000-0000-000000999811',978001,'COMPLETED','DONE','{}','{}',repeat('f',32),'2026-01-01T00:00:00Z'),
                ('00000000-0000-0000-0000-000000999822','00000000-0000-0000-0000-000000999811',978001,'COMPLETED','DONE','{}','{}',repeat('f',32),'2026-01-01T00:00:00Z'),
                ('00000000-0000-0000-0000-000000999823','00000000-0000-0000-0000-000000999811',978001,'QUEUED','NONE','{}','{}',repeat('f',32),'2026-01-01T00:00:00Z');
            INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,outcome,coverage,recorded_at)
            VALUES('00000000-0000-0000-0000-000000999851',978001,'00000000-0000-0000-0000-000000999811',978004,999831,999841,'file-a-head','MET','{}',now());
            UPDATE review_item SET state='JUDGED',result_id='00000000-0000-0000-0000-000000999851',job_id='00000000-0000-0000-0000-000000999821'
            WHERE review_id='00000000-0000-0000-0000-000000999811' AND item_id=999841;
            COMMIT;
            """);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
            INSERT INTO review_job(id,review_id,workspace_id,status,input,config_snapshot,trace_id,submitted_at)
            VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000999811',978001,'QUEUED','{}','{}',repeat('f',32),'2026-01-01T00:00:00Z')
            """))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("uk_review_job_active_review");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
            SELECT '00000000-0000-0000-0000-000000999811',978001,978004,item_id,'not-a-file'
            FROM review WHERE id='00000000-0000-0000-0000-000000978011'
            """))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("outside review scope");
        PracticeMigrationFixture.execute(database, """
            DELETE FROM review_job WHERE id='00000000-0000-0000-0000-000000999821';
            UPDATE review_job SET status='CANCELLED',processing_status='DONE',cancellation_reason='CANCELLED_BY_ADMIN',completed_at=now() WHERE id='00000000-0000-0000-0000-000000999823';
            UPDATE review SET status='PARTIAL',stop_reason='DEADLINE',completed_at=now() WHERE id='00000000-0000-0000-0000-000000999811';
            INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,budget_usd,ordered_at)
            SELECT '00000000-0000-0000-0000-000000999812',workspace_id,item_id,kind,item_version,origin,'QUEUED',budget_usd,now()
            FROM review WHERE id='00000000-0000-0000-0000-000000999811';
            INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)
            VALUES('00000000-0000-0000-0000-000000999812',978001,978004,999831,'READY');
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version,state,result_id)
            VALUES('00000000-0000-0000-0000-000000999812',978001,978004,999841,'file-a-head','REUSED','00000000-0000-0000-0000-000000999851');
            """);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (job_id IS NULL)::text || '|' || state FROM review_item WHERE"
                                + " review_id='00000000-0000-0000-0000-000000999811' AND item_id=999841"))
                .containsExactly("true|JUDGED");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT a.state || '|' || r.status FROM review_item a JOIN review r ON"
                                + " r.id=a.review_id WHERE a.review_id='00000000-0000-0000-0000-000000999811' AND"
                                + " a.item_id=999842"))
                .containsExactly("LISTED|PARTIAL");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT state || '|' || r.review_id FROM review_item a JOIN result r ON"
                                + " r.id=a.result_id WHERE a.review_id='00000000-0000-0000-0000-000000999812'"))
                .containsExactly("REUSED|00000000-0000-0000-0000-000000999811");
    }
}
