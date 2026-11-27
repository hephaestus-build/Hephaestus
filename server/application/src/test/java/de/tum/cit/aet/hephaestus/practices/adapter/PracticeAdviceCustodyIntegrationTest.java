package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQL ties every advice row to one developer and workspace (ADR 0160). A source can only be the developer's own result,
 * and a retired entry carries its reason.
 */
class PracticeAdviceCustodyIntegrationTest extends BaseIntegrationTest {
    private static final String TEXT = "advice-custody-canary";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private Scenario scenario;
    private long ws;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario(DefinitionTestFixtures.definition());
        ws = scenario.workspace().getId();
    }

    @Test
    void shouldRefuseASourceThatIsAnotherPersonsResult() {
        var other = data.member(ws);
        Result theirs = result(new Scenario(scenario.workspace(), other, scenario.practice(), scenario.item()));
        UUID entry = entry(scenario);

        assertThatThrownBy(() -> revision(entry, 1, List.of(theirs.getId()), List.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseARetiredEntryWithoutAReason() {
        UUID entry = entry(scenario);

        assertThatThrownBy(() -> jdbc.sql("UPDATE practice_advice SET state='RETIRED', retired_at=:at WHERE id=:id")
                        .param("at", Timestamp.from(Instant.now()))
                        .param("id", entry)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("UPDATE practice_advice SET state='RETIRED', retired_at=:at, retire_reason='RESOLVED'"
                                + " WHERE id=:id")
                        .param("at", Timestamp.from(Instant.now()))
                        .param("id", entry)
                        .update())
                .isOne();
    }

    private Result result(Scenario on) {
        return data.result(on, ResultOutcome.NOT_MET, Instant.now().minus(Duration.ofHours(1)));
    }

    private UUID entry(Scenario of) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO practice_advice(id,workspace_id,person_account_id,practice_id,state,version,created_at)"
                        + " VALUES(:id,:ws,:person,:practice,'CURRENT',0,:at)")
                .param("id", id)
                .param("ws", of.workspace().getId())
                .param("person", of.account().getId())
                .param("practice", of.practice().getId())
                .param("at", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    private UUID revision(UUID entry, int number, List<UUID> cited, List<UUID> seen) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO practice_advice_revision(id,workspace_id,person_account_id,advice_id,number,text,"
                            + "review_id,cited_count,source_count,written_at)"
                            + " SELECT :id,a.workspace_id,a.person_account_id,a.id,:number,:text,:review,:cited,:sources,"
                            + ":at FROM practice_advice a WHERE a.id=:entry")
                    .param("id", id)
                    .param("number", number)
                    .param("text", TEXT)
                    .param("review", UUID.randomUUID())
                    .param("cited", cited.size())
                    .param("sources", cited.size() + seen.size())
                    .param("at", Timestamp.from(Instant.now()))
                    .param("entry", entry)
                    .update();
            int ordinal = 0;
            for (UUID result : cited) source(id, result, true, ordinal++);
            for (UUID result : seen) source(id, result, false, ordinal++);
        });
        return id;
    }

    private void source(UUID revision, UUID result, boolean cited, int ordinal) {
        jdbc.sql("INSERT INTO practice_advice_source(id,workspace_id,person_account_id,revision_id,result_id,cited,"
                        + "ordinal) SELECT :id,r.workspace_id,r.person_account_id,r.id,:result,:cited,:ordinal"
                        + " FROM practice_advice_revision r WHERE r.id=:revision")
                .param("id", UUID.randomUUID())
                .param("result", result)
                .param("cited", cited)
                .param("ordinal", ordinal)
                .param("revision", revision)
                .update();
    }
}
