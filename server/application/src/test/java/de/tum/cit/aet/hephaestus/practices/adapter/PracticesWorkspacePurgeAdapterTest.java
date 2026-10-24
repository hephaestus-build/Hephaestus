package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class PracticesWorkspacePurgeAdapterTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    @Autowired
    PracticesWorkspacePurgeAdapter purge;

    @Autowired
    JdbcClient jdbc;

    @Test
    @Transactional
    void purgeDeletesFinalRowsOnlyForItsWorkspace() {
        var mine = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var other = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        purge.deleteWorkspaceData(mine.getWorkspaceId());
        assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM result WHERE workspace_id=:ws AND id=:id)")
                        .param("ws", mine.getWorkspaceId())
                        .param("id", mine.getId())
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM result WHERE workspace_id=:ws AND id=:id)")
                        .param("ws", other.getWorkspaceId())
                        .param("id", other.getId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM review WHERE workspace_id=:ws AND id=:id)")
                        .param("ws", mine.getWorkspaceId())
                        .param("id", mine.getReviewId())
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM review WHERE workspace_id=:ws AND id=:id)")
                        .param("ws", other.getWorkspaceId())
                        .param("id", other.getReviewId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
