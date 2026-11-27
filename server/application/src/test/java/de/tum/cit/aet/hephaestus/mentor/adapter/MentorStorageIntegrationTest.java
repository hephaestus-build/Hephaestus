package de.tum.cit.aet.hephaestus.mentor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MentorStorageIntegrationTest extends BaseIntegrationTest {
    private static final List<String> TABLES = List.of("mentor_event", "mentor_request", "mentor_source");

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private MentorPersonDataCatalog catalog;

    @Autowired
    private MentorWorkspaceCleanup cleanup;

    @Autowired
    private SlackWorkspaceContentEraser slackContent;

    @Test
    void shouldHideAnotherAccountsMentorRowsButNameTheirOwnerWhenAnotherOwnerOrNoOwnerIsBound() {
        long first = account();
        long second = account();
        UUID foreign = asOwner(second, () -> seed(second));

        asOwner(first, () -> {
            for (String table : TABLES) {
                assertThat(jdbc.queryForList("SELECT id FROM " + table + " WHERE account_id = ?", UUID.class, second))
                        .as(table)
                        .isEmpty();
            }
            assertThat(jdbc.update("UPDATE mentor_request SET state = 'STOPPED' WHERE source_id = ?", foreign))
                    .isZero();
            assertThat(jdbc.update("DELETE FROM mentor_event WHERE source_id = ?", foreign))
                    .isZero();
            return foreign;
        });
        assertThat(jdbc.queryForList("SELECT id FROM mentor_source WHERE id = ?", UUID.class, foreign))
                .as("no owner bound")
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT public.mentor_source_owner(7, 'D1', ?)", Long.class, foreign.toString()))
                .as("the owner selector names only the account")
                .isEqualTo(second);
        assertThatThrownBy(() -> asOwner(first, () -> seed(second)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        asOwner(second, () -> {
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM mentor_request WHERE source_id = ?", String.class, foreign))
                    .isEqualTo("OPEN");
            return foreign;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mentor_request", "mentor_event"})
    void shouldRejectASourceReferenceToAnotherAccount(String table) {
        long owner = account();
        long other = account();
        UUID ownSource = asOwner(owner, () -> seed(owner));
        UUID otherSource = asOwner(other, () -> seedSource(other, 1L));

        assertThatThrownBy(() -> asOwner(owner, () -> {
                    jdbc.update("UPDATE " + table + " SET source_id = ? WHERE source_id = ?", otherSource, ownSource);
                    return ownSource;
                }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("violates foreign key constraint");
    }

    @Test
    void shouldRejectAnEventReferenceToAnotherAccountsRequest() {
        long owner = account();
        long other = account();
        UUID ownSource = asOwner(owner, () -> seed(owner));
        UUID otherSource = asOwner(other, () -> seed(other));
        UUID otherRequest = asOwner(
                other,
                () -> jdbc.queryForObject("SELECT id FROM mentor_request WHERE source_id = ?", UUID.class, otherSource));

        assertThatThrownBy(() -> asOwner(owner, () -> {
                    jdbc.update("UPDATE mentor_event SET request_id = ? WHERE source_id = ?", otherRequest, ownSource);
                    return ownSource;
                }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("violates foreign key constraint");
    }

    @Test
    void shouldExportMetadataAndEraseOnlyTheSubjectsMentorRowsWhenTheAccountIsErased() {
        long subject = account();
        long other = account();
        UUID erased = asOwner(subject, () -> seed(subject));
        UUID kept = asOwner(other, () -> seed(other));
        var scope = new PersonScope(subject, List.of(), List.of());

        asOwner(subject, () -> {
            for (PersonDataContributor store : catalog.contributors().stream()
                    .sorted(Comparator.comparingInt(PersonDataContributor::getOrder))
                    .toList()) {
                var selection = store.select(scope);
                assertThat(store.export(selection, scope))
                        .as(store.store())
                        .singleElement()
                        .satisfies(row ->
                                assertThat(row.path("account_id").asLong()).isEqualTo(subject))
                        .satisfies(
                                row -> assertThat(row.has("scope_reader_epoch")).isFalse());
                assertThat(store.erase(selection, scope)).as(store.store()).isOne();
            }
            return erased;
        });

        asOwner(subject, () -> {
            for (String table : TABLES) {
                assertThat(jdbc.queryForList("SELECT id FROM " + table + " WHERE account_id = ?", UUID.class, subject))
                        .as(table)
                        .isEmpty();
            }
            return erased;
        });
        asOwner(other, () -> {
            assertThat(jdbc.queryForList("SELECT id FROM mentor_source WHERE id = ?", UUID.class, kept))
                    .containsExactly(kept);
            return kept;
        });
    }

    /** A workspace purge and a Slack disconnect or restore, which both run with no owner bound. */
    @ParameterizedTest
    @ValueSource(strings = {"purge", "slack-disconnect"})
    void shouldEraseEveryAccountsRowsOfOnlyThatWorkspaceWhenItIsPurgedOrItsSlackIsDisconnected(String path) {
        long first = account();
        long second = account();
        long purged = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        UUID firstErased = asOwner(first, () -> seed(first, purged));
        UUID secondErased = asOwner(second, () -> seed(second, purged));
        UUID kept = asOwner(first, () -> seed(first, purged - 1));

        if (path.equals("purge")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.deleteWorkspaceData(purged));
        } else {
            slackContent.eraseWorkspace(purged);
        }
        asOwner(first, () -> {
            assertThat(jdbc.queryForList(
                            "SELECT id FROM mentor_source WHERE id IN (?, ?)", UUID.class, firstErased, kept))
                    .containsExactly(kept);
            assertThat(jdbc.queryForList("SELECT id FROM mentor_request WHERE source_id = ?", UUID.class, firstErased))
                    .isEmpty();
            return kept;
        });
        asOwner(second, () -> {
            assertThat(jdbc.queryForList("SELECT id FROM mentor_event WHERE source_id = ?", UUID.class, secondErased))
                    .isEmpty();
            return secondErased;
        });
    }

    /** One source with its event and request, written through the row policy of the bound owner. */
    private UUID seed(long account) {
        return seed(account, 1L);
    }

    private UUID seed(long account, long workspace) {
        UUID source = seedSource(account, workspace);
        jdbc.update("""
                INSERT INTO mentor_event(id, account_id, binding_id, native_event_id, kind, source_id, disposition,
                    source_ref, occurred_at, received_at)
                VALUES (?, ?, 7, ?, 'MESSAGE', ?, 'OPENED', 'provider-event:1', now(), now())
                """, UUID.randomUUID(), account, source.toString(), source);
        jdbc.update("""
                INSERT INTO mentor_request(id, account_id, source_id, version, state, audience_transport,
                    audience_connection_id, audience_container_id, scope_account_generation, scope_reader_epoch,
                    scope_consent_epoch, scope_authority, opened_at, deadline)
                VALUES (?, ?, ?, 1, 'OPEN', 'SLACK', 7, 'D1', 1, 1, 1, 'PERSONAL', now(), now() + interval '1 hour')
                """, UUID.randomUUID(), account, source);
        return source;
    }

    private UUID seedSource(long account, long workspace) {
        UUID source = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mentor_source(id, account_id, binding_id, workspace_id, container_id, native_message_id,
                    producer, revision, state)
                VALUES (?, ?, 7, ?, 'D1', ?, 'MENTOR', '1700000000.000100', 'LIVE')
                """, source, account, workspace, source.toString());
        return source;
    }

    private UUID asOwner(long account, Supplier<UUID> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            owners.ofJob(account);
            return work.get();
        }));
    }

    private long account() {
        return Objects.requireNonNull(accounts.save(new Account("Mentor owner")).getId());
    }
}
