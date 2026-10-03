package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.stream.LongStream;
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

    @Test
    void shouldPreserveFrozenTimestampKeysAcrossSessionTimeZonesAndLargeScopes() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var plain = jdbc.getJdbcTemplate();
            plain.execute(
                    "CREATE TEMP TABLE person_data_key_test(id bigint, occurred_at timestamptz, owner_id bigint, body text, PRIMARY KEY(id,occurred_at)) ON COMMIT DROP");
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
            assertThat(store.export(selected)).hasSize(1);
            plain.execute("SET LOCAL TIME ZONE 'Europe/Berlin'");
            assertThat(store.export(selected)).hasSize(1);
            assertThat(store.export(selected).getFirst().path("body").asString())
                    .isEqualTo("Target content");
            assertThat(store.erase(selected)).isEqualTo(1);
            assertThat(store.erase(selected)).isZero();
            assertThat(plain.queryForObject("SELECT body FROM person_data_key_test", String.class))
                    .isEqualTo("Other content");
            assertThat(store.select(new PersonScope(null, List.of(), List.of())).rows())
                    .isEmpty();
        });
    }

    @Test
    void shouldNotEraseARowWhoseFrozenAccountAttributionChanged() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var plain = jdbc.getJdbcTemplate();
            plain.execute(
                    "CREATE TEMP TABLE person_data_key_test(id bigint PRIMARY KEY, owner_id bigint, body text) ON COMMIT DROP");
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
            var selected = store.select(new PersonScope(null, List.of(), List.of(42L)));
            assertThat(selected.rows()).hasSize(1);
            plain.execute("UPDATE person_data_key_test SET owner_id=84 WHERE id=1");
            assertThat(store.erase(selected)).isZero();
            assertThat(plain.queryForObject("SELECT owner_id FROM person_data_key_test", Long.class))
                    .isEqualTo(84);
            assertThat(plain.queryForObject("SELECT body FROM person_data_key_test", String.class))
                    .isEqualTo("Shared operational facts");
        });
    }
}
