package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRetentionService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advice lasts no longer than its evidence (ADR 0160): each path that deletes a result first removes the text of every
 * advice revision with that result as a source, in the same transaction. The developer's own advice goes with their
 * erasure, also advice written after the preview, and no one else's request selects it. SQL ties every advice row to one
 * developer and workspace.
 */
class PracticeAdviceCustodyIntegrationTest extends BaseIntegrationTest {
    private static final String TEXT = "advice-custody-canary";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PracticesPersonDataCatalog practices;

    @Autowired
    private DerivedResultEraser withdrawal;

    @Autowired
    private ResultRetentionService retention;

    @Autowired
    private PracticesWorkspacePurgeAdapter purge;

    @Autowired
    private PracticeAdvicePersonDataCatalog advice;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PersonDataService personData;

    private Scenario scenario;
    private long ws;
    private long developer;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario(DefinitionTestFixtures.definition());
        ws = scenario.workspace().getId();
        developer = Objects.requireNonNull(scenario.account().getId());
    }

    @Test
    void shouldRemoveTheTextOfTheErasedDevelopersAdviceAndKeepAnotherDevelopersAdvice() {
        Result erased = result(scenario);
        UUID cited = revision(entry(scenario), 1, List.of(erased.getId()), List.of());
        Scenario theirs = theirs();
        UUID kept = revision(entry(theirs), 1, List.of(result(theirs).getId()), List.of());

        eraseInOrder(practices.contributors(), preview(practices.contributors(), scope(developer)), scope(developer));

        assertThat(exists("result", erased.getId())).isFalse();
        assertStale(cited);
        assertThat(text(kept)).isEqualTo(TEXT);
    }

    @Test
    void shouldRemoveTheTextOfAdviceWrittenAfterTheErasureWasAdmitted() {
        Result erased = result(scenario);
        var frozen = preview(practices.contributors(), scope(developer));
        UUID late = revision(entry(scenario), 1, List.of(erased.getId()), List.of());

        eraseInOrder(practices.contributors(), frozen, scope(developer));

        assertStale(late);
    }

    @Test
    void shouldDeleteTheDevelopersAdviceAlsoWhenItWasWrittenAfterThePreview() {
        var frozen = preview(advice.contributors(), scope(developer));
        UUID entry = entry(scenario);
        revision(entry, 1, List.of(result(scenario).getId()), List.of());
        UUID theirs = entry(theirs());

        eraseInOrder(advice.contributors(), frozen, scope(developer));

        assertThat(exists("practice_advice", entry)).isFalse();
        assertThat(count("practice_advice_revision")).isZero();
        assertThat(count("practice_advice_source")).isZero();
        assertThat(exists("practice_advice", theirs))
                .as("another developer's advice")
                .isTrue();
    }

    @Test
    void shouldNeverSelectADevelopersAdviceForAnotherPerson() {
        revision(entry(scenario), 1, List.of(result(scenario).getId()), List.of());
        long other = Objects.requireNonNull(data.member(ws).getId());

        advice.contributors()
                .forEach(store -> assertThat(store.select(scope(other)).rows())
                        .as(store.store())
                        .isEmpty());
    }

    @Test
    void shouldKeepAdviceTextOutOfTheInstanceAdminDownload() {
        UUID revision = revision(entry(scenario), 1, List.of(result(scenario).getId()), List.of());
        var admin = new Account("Advice export operator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        var preview = personData.preview(adminId, developer, List.of());

        // The response of POST /admin/person-data/{id}/export serializes this bundle.
        var revisions =
                personData.export(preview.request().getId()).path("stores").path("practice_advice_revision");
        assertThat(revisions.size()).as("the developer's revision is listed").isOne();
        assertThat(revisions.path(0).path("id").asString()).isEqualTo(revision.toString());
        assertThat(revisions.toString()).doesNotContain(TEXT);
    }

    @Test
    void shouldRemoveTheTextOfEveryRevisionWithAWithdrawnResultAsASourceCitedOrNot() {
        Result withdrawn = result(scenario);
        Result kept = result(data.onOtherWork(scenario));
        UUID entry = entry(scenario);
        UUID cited = revision(entry, 1, List.of(withdrawn.getId()), List.of());
        UUID seen = revision(entry, 2, List.of(kept.getId()), List.of(withdrawn.getId()));
        UUID unrelated = revision(entry, 3, List.of(kept.getId()), List.of());

        transactions.executeWithoutResult(status -> withdrawal.erase(ws, List.of(withdrawn.getId())));

        assertThat(exists("result", withdrawn.getId())).isFalse();
        assertStale(cited);
        assertStale(seen);
        assertThat(text(unrelated)).isEqualTo(TEXT);
    }

    @Test
    void shouldRemoveTheTextBeforeRetentionDeletesTheResultAndKeepNoResultAlive() {
        Result expired = result(scenario);
        UUID revision = revision(entry(scenario), 1, List.of(expired.getId()), List.of());

        retention.purgeWorkspace(ws, Instant.now().plus(Duration.ofDays(1)));

        assertThat(exists("result", expired.getId()))
                .as("advice keeps no result alive")
                .isFalse();
        assertStale(revision);
        assertThat(count("practice_advice")).as("the entry stays").isOne();
    }

    @Test
    void shouldDeleteTheWorkspacesAdviceBeforeItsResultsOnPurge() {
        UUID entry = entry(scenario);
        revision(entry, 1, List.of(result(scenario).getId()), List.of());

        var owner = PostgreSQLTestContainer.ownerJdbc();
        owner.execute("""
                CREATE FUNCTION test_check_advice_before_result_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM practice_advice_source s JOIN practice_advice_revision r
                          ON r.workspace_id = s.workspace_id AND r.id = s.revision_id
                        WHERE s.workspace_id = OLD.workspace_id AND s.result_id = OLD.id AND r.text IS NOT NULL
                    ) THEN
                        RAISE EXCEPTION 'Advice still has text when its source result is deleted';
                    END IF;
                    RETURN OLD;
                END;
                $$;
                CREATE TRIGGER test_check_advice_before_result_delete BEFORE DELETE ON result
                    FOR EACH ROW EXECUTE FUNCTION test_check_advice_before_result_delete();
                """);
        try {
            transactions.executeWithoutResult(status -> purge.deleteWorkspaceData(ws));
        } finally {
            owner.execute("""
                    DROP TRIGGER test_check_advice_before_result_delete ON result;
                    DROP FUNCTION test_check_advice_before_result_delete();
                    """);
        }

        assertThat(count("practice_advice")).isZero();
        assertThat(count("practice_advice_revision")).isZero();
        assertThat(count("practice_advice_source")).isZero();
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

    private void assertStale(UUID revision) {
        assertThat(text(revision)).as("text of %s", revision).isNull();
        assertThat(jdbc.sql("SELECT stale_at IS NOT NULL FROM practice_advice_revision WHERE id=:id")
                        .param("id", revision)
                        .query(Boolean.class)
                        .single())
                .as("stale_at of %s", revision)
                .isTrue();
    }

    private @Nullable String text(UUID revision) {
        return jdbc.sql("SELECT text FROM practice_advice_revision WHERE id=:id")
                .param("id", revision)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private Scenario theirs() {
        return new Scenario(scenario.workspace(), data.member(ws), scenario.practice(), scenario.item());
    }

    /** Freezes each store's selection, as the request's admission does. */
    private static Map<String, PersonDataSelection> preview(List<PersonDataContributor> stores, PersonScope scope) {
        return stores.stream().collect(Collectors.toMap(PersonDataContributor::store, store -> store.select(scope)));
    }

    /** Erases each store in its order and in its own transaction, as the request's steps do. */
    private void eraseInOrder(
            List<PersonDataContributor> stores, Map<String, PersonDataSelection> frozen, PersonScope scope) {
        stores.stream()
                .sorted(Comparator.comparingInt(PersonDataContributor::getOrder))
                .forEach(store -> transactions.executeWithoutResult(
                        status -> store.erase(Objects.requireNonNull(frozen.get(store.store())), scope)));
    }

    private static PersonScope scope(long account) {
        return new PersonScope(account, List.of(), List.of());
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
