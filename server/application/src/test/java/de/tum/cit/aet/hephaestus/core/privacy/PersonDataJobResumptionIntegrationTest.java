package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.*;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.*;
import de.tum.cit.aet.hephaestus.testconfig.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL transactions prove a store mutation and its receipt cannot commit separately. */
class PersonDataJobResumptionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private PersonSuppressionService suppression;

    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private IssuedJwtRepository tokens;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Test
    void failedStoreRollsBackAndResumeSkipsCommittedStoresWithoutRecordingContent() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://resume.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", provider));
        jdbc.update("UPDATE \"user\" SET name='Target profile' WHERE id=?", target.getId());
        jdbc.update("UPDATE \"user\" SET name='Other profile' WHERE id=?", other.getId());
        for (long userId : List.of(Objects.requireNonNull(target.getId()), Objects.requireNonNull(other.getId()))) {
            jdbc.update(
                    "INSERT INTO user_preferences(user_id,participate_in_research,ai_review_enabled) VALUES (?,false,false)",
                    userId);
        }
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var firstCalls = new AtomicInteger();
        var secondCalls = new AtomicInteger();
        var failOnce = new AtomicBoolean(true);
        var preferences =
                new JdbcPersonDataStore(
                        namedJdbc,
                        mapper,
                        "preferences_step",
                        "user_preferences",
                        "t.user_id = ANY(:users)",
                        "user_id,participate_in_research,ai_review_enabled",
                        "user_id",
                        "",
                        0) {
                    @Override
                    public long erase(PersonDataSelection selection) {
                        firstCalls.incrementAndGet();
                        return super.erase(selection);
                    }
                };
        var profile =
                new JdbcPersonDataStore(
                        namedJdbc,
                        mapper,
                        "profile_step",
                        "user",
                        "t.id = ANY(:users)",
                        "id,name",
                        "id",
                        "name=NULL",
                        1) {
                    @Override
                    public long erase(PersonDataSelection selection) {
                        secondCalls.incrementAndGet();
                        long count = super.erase(selection);
                        if (failOnce.getAndSet(false))
                            throw new IllegalStateException("PERSONAL-CONTENT-FAILURE-CANARY");
                        return count;
                    }
                };
        var registry = new PersonDataRegistry(List.of(new TransactionProofCatalog(List.of(preferences, profile))));
        var service = new PersonDataService(
                resolver,
                registry,
                requests,
                suppression,
                fence,
                new PostgresPersonDataCopyFence(Objects.requireNonNull(jdbc.getDataSource()), jdbc),
                tokens,
                transactions,
                mapper,
                jdbc);
        var tx = new TransactionTemplate(transactions);
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        var preview = Objects.requireNonNull(tx.execute(status -> service.preview(adminId, null, identities)));
        UUID requestId = preview.request().getId();
        tx.executeWithoutResult(status -> service.requestErasure(requestId, adminId, true));
        service.run(requestId);

        var failed = requests.findById(requestId).orElseThrow();
        assertThat(failed.getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("STORE_ERASURE_FAILED");
        assertThat(mapper.readTree(failed.getCompletedJson())
                        .path("preferences_step")
                        .asLong())
                .isEqualTo(1L);
        assertThat(mapper.readTree(failed.getCompletedJson()).has("profile_step"))
                .isFalse();
        assertThat(failed.getCompletedJson()).doesNotContain("PERSONAL-CONTENT", "Target profile");
        assertThat(preferenceCount(target.getId())).isZero();
        assertThat(profileName(target.getId())).isEqualTo("Target profile");
        assertThat(suppression.isSuppressed(provider.getId(), "42", null)).isTrue();
        assertThat(preferenceCount(other.getId())).isEqualTo(1L);
        assertThat(profileName(other.getId())).isEqualTo("Other profile");

        tx.executeWithoutResult(status -> service.requestErasure(requestId, adminId, true));
        service.run(requestId);
        var completed = requests.findById(requestId).orElseThrow();
        assertThat(completed.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(completed.getAdministratorAccountId()).isEqualTo(adminId);
        assertThat(completed.getScopeJson()).isNull();
        assertThat(completed.getSelectionsJson()).isNull();
        assertThat(completed.getFailureCode()).isNull();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(mapper.readTree(completed.getCompletedJson())
                        .path("profile_step")
                        .asLong())
                .isEqualTo(1L);
        assertThat(profileName(target.getId())).isNull();
        assertThat(suppression.isSuppressed(provider.getId(), "42", null)).isTrue();
        assertThat(preferenceCount(other.getId())).isEqualTo(1L);
        assertThat(profileName(other.getId())).isEqualTo("Other profile");
        tx.executeWithoutResult(status -> service.requestErasure(requestId, adminId, true));
        service.run(requestId);
        assertThat(firstCalls.get()).isEqualTo(1);
        assertThat(secondCalls.get()).isEqualTo(2);
    }

    private long preferenceCount(Long userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM user_preferences WHERE user_id=?", Long.class, userId));
    }

    private @org.jspecify.annotations.Nullable String profileName(Long userId) {
        return jdbc.queryForObject("SELECT name FROM \"user\" WHERE id=?", String.class, userId);
    }

    @PersonDataStores({"preferences_step", "profile_step"})
    private record TransactionProofCatalog(List<PersonDataContributor> contributors) implements PersonDataCatalog {}
}
