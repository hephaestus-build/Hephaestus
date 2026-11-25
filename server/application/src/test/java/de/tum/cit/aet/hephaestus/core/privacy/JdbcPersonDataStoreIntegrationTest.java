package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcPersonDataStoreIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PlatformTransactionManager transactions;

    @AfterEach
    void dropKeyFixture() {
        PostgreSQLTestContainer.ownerJdbc().execute("DROP TABLE IF EXISTS public.person_data_key_test");
    }

    @Test
    void shouldPreserveFrozenTimestampKeysAcrossSessionTimeZonesAndLargeScopes() {
        PostgreSQLTestContainer.ownerJdbc().execute("""
            CREATE TABLE public.person_data_key_test (
                id bigint, occurred_at timestamptz, owner_id bigint, body text, PRIMARY KEY (id, occurred_at)
            )
            """);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var plain = jdbc.getJdbcTemplate();
            plain.execute(
                    "INSERT INTO person_data_key_test VALUES (1,'2026-10-01T12:00:00Z',42,'Target content'),(2,'2026-10-01T12:00:00Z',70001,'Other content')");
            var store = new JdbcPersonDataStore(
                    jdbc,
                    mapper,
                    "key_test",
                    "person_data_key_test",
                    "t.owner_id = ANY(:users)",
                    "id,occurred_at,body",
                    "id,occurred_at",
                    "",
                    0);
            var scope = new PersonScope(
                    null, List.of(), LongStream.rangeClosed(1, 70000).boxed().toList());
            plain.execute("SET LOCAL TIME ZONE 'UTC'");
            var selected = store.select(scope);
            assertThat(selected.rows()).hasSize(1);
            assertThat(store.export(selected, scope)).hasSize(1);
            plain.execute("SET LOCAL TIME ZONE 'Europe/Berlin'");
            assertThat(store.export(selected, scope)).hasSize(1);
            assertThat(store.export(selected, scope).getFirst().path("body").asString())
                    .isEqualTo("Target content");
            assertThat(store.erase(selected, scope)).isEqualTo(1);
            assertThat(store.erase(selected, scope)).isZero();
            assertThat(plain.queryForObject("SELECT body FROM person_data_key_test", String.class))
                    .isEqualTo("Other content");
            assertThat(store.select(new PersonScope(null, List.of(), List.of())).rows())
                    .isEmpty();
        });
    }

    @Test
    void shouldRedactOnlyFrozenReferencesUsingTheOriginalSubjectScope() {
        PostgreSQLTestContainer.ownerJdbc()
                .execute(
                        "CREATE TABLE public.person_data_key_test (id bigint PRIMARY KEY, subject_id bigint, actor_id bigint)");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var plain = jdbc.getJdbcTemplate();
            plain.execute("INSERT INTO person_data_key_test VALUES (1,42,84),(2,84,42)");
            var store = new JdbcPersonDataStore(
                    jdbc,
                    mapper,
                    "key_test",
                    "person_data_key_test",
                    "t.subject_id=:account OR t.actor_id=:account",
                    "id,subject_id,actor_id",
                    "id",
                    "subject_id=CASE WHEN subject_id=:account THEN NULL ELSE subject_id END,actor_id=CASE WHEN actor_id=:account THEN NULL ELSE actor_id END",
                    0);
            var scope = new PersonScope(42L, List.of(), List.of());
            var selected = store.select(scope);
            plain.execute("INSERT INTO person_data_key_test VALUES (3,42,84)");
            assertThat(store.erase(selected, scope)).isEqualTo(2);
            assertThat(plain.queryForObject("SELECT subject_id FROM person_data_key_test WHERE id=1", Long.class))
                    .isNull();
            assertThat(plain.queryForObject("SELECT actor_id FROM person_data_key_test WHERE id=1", Long.class))
                    .isEqualTo(84L);
            assertThat(plain.queryForObject("SELECT subject_id FROM person_data_key_test WHERE id=2", Long.class))
                    .isEqualTo(84L);
            assertThat(plain.queryForObject("SELECT actor_id FROM person_data_key_test WHERE id=2", Long.class))
                    .isNull();
            assertThat(plain.queryForObject("SELECT subject_id FROM person_data_key_test WHERE id=3", Long.class))
                    .isEqualTo(42L);
        });
    }

    @Test
    void shouldNotEraseARowWhoseFrozenAccountAttributionChanged() {
        PostgreSQLTestContainer.ownerJdbc()
                .execute(
                        "CREATE TABLE public.person_data_key_test (id bigint PRIMARY KEY, owner_id bigint, body text)");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var plain = jdbc.getJdbcTemplate();
            plain.execute("INSERT INTO person_data_key_test VALUES (1,42,'Shared operational facts')");
            var store = new JdbcPersonDataStore(
                    jdbc,
                    mapper,
                    "key_test",
                    "person_data_key_test",
                    "t.owner_id = ANY(:users)",
                    "id,owner_id,body",
                    "id,owner_id",
                    "owner_id=NULL",
                    0);
            var selectedScope = new PersonScope(null, List.of(), List.of(42L));
            var selected = store.select(selectedScope);
            assertThat(selected.rows()).hasSize(1);
            plain.execute("UPDATE person_data_key_test SET owner_id=84 WHERE id=1");
            assertThat(store.erase(selected, selectedScope)).isZero();
            assertThat(plain.queryForObject("SELECT owner_id FROM person_data_key_test", Long.class))
                    .isEqualTo(84);
            assertThat(plain.queryForObject("SELECT body FROM person_data_key_test", String.class))
                    .isEqualTo("Shared operational facts");
        });
    }
}
