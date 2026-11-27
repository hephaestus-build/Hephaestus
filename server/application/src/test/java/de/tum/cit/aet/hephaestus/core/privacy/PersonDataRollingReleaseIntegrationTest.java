package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.LiveJdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * An erasure across a rolling release that adds a live store (#5382). Two services share one database and differ only
 * in their inventory: the older one lacks the live store {@code avatar_step} that the newer one adds. Neither direction
 * may stop the erasure: an older replica leaves a request it cannot read, and a newer replica erases its live store for
 * a request that an older replica froze.
 */
class PersonDataRollingReleaseIntegrationTest extends BaseIntegrationTest {
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
    private IdentityRestrictionService suppression;

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

    private final SimpleMeterRegistry olderMeters = new SimpleMeterRegistry();
    private PersonDataService older;
    private PersonDataService newer;
    private TransactionTemplate tx;
    private IdentityProvider provider;
    private User target;
    private long adminId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://rolling.example.test"));
        target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        jdbc.update(
                "UPDATE \"user\" SET name='Target profile', avatar_url='https://avatar.example.test/42' WHERE id=?",
                target.getId());
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        adminId = Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var profile = new JdbcPersonDataStore(
                namedJdbc, mapper, "profile_step", "user", "t.id = ANY(:users)", "id,name", "id", "name=NULL", 0);
        var avatar = new LiveJdbcPersonDataStore(
                namedJdbc,
                mapper,
                "avatar_step",
                "user",
                "t.id = ANY(:users)",
                "id,avatar_url",
                "id",
                "avatar_url=NULL",
                1);
        older = service(new OlderCatalog(List.of(profile)), olderMeters);
        newer = service(new NewerCatalog(List.of(profile, avatar)), new SimpleMeterRegistry());
        tx = new TransactionTemplate(transactions);
    }

    @Test
    void shouldLeaveARequestThatANewerReleaseFrozeAndCompleteItThere() {
        UUID id = admit(newer);

        older.run(id);

        assertWaiting(id);
        assertThat(profileName()).isEqualTo("Target profile");
        newer.run(id);
        assertThat(requests.findById(id).orElseThrow().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(profileName()).isNull();
        assertThat(avatar()).isNull();
    }

    @Test
    void shouldLeaveARequestWhoseReceiptsNameAStoreOfANewerRelease() {
        UUID id = admit(older);
        // A newer replica erased its live store, and the pass ended before the request completed.
        jdbc.update(
                "UPDATE person_data_request SET completed_json=? WHERE id=?",
                "{\"avatar_step\":{\"count\":1,\"erasedAt\":\"2026-11-27T00:00:00Z\",\"administratorAccountId\":"
                        + adminId + "}}",
                id);

        older.run(id);

        assertWaiting(id);
        newer.run(id);
        assertThat(requests.findById(id).orElseThrow().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(profileName()).isNull();
        assertThat(avatar()).isNull();
    }

    @Test
    void shouldEraseALiveStoreThatAnOlderRequestLacks() {
        UUID id = admit(older);

        newer.run(id);

        var request = requests.findById(id).orElseThrow();
        assertThat(request.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(request.getFailureCode()).isNull();
        assertThat(profileName()).isNull();
        assertThat(avatar()).isNull();
    }

    private UUID admit(PersonDataService by) {
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        UUID id = Objects.requireNonNull(tx.execute(status -> by.preview(adminId, null, identities)))
                .request()
                .getId();
        tx.executeWithoutResult(status -> by.requestErasure(id, adminId, true));
        return id;
    }

    /** The older replica changed nothing, did not fail the request and counted the deferral. */
    private void assertWaiting(UUID id) {
        var request = requests.findById(id).orElseThrow();
        assertThat(request.getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(request.getFailureCode()).isNull();
        assertThat(olderMeters
                        .counter(CoreMetrics.PERSON_DATA_ERASURE_DEFERRALS)
                        .count())
                .isOne();
    }

    private PersonDataService service(PersonDataCatalog catalog, SimpleMeterRegistry meters) {
        List<PersonDataCatalog> catalogs = List.of(catalog);
        return new PersonDataService(
                new PersonDataScopeResolver(resolver, List.of(), new OwnerScope(jdbc)),
                new PersonDataRegistry(catalogs),
                requests,
                new PersonDataErasureHold(
                        suppression,
                        fence,
                        new PostgresPersonDataCopyFence(Objects.requireNonNull(jdbc.getDataSource()), jdbc)),
                tokens,
                transactions,
                mapper,
                jdbc,
                meters,
                mock(PrivacyJournal.class),
                mock(PrivacyJournalShipping.class));
    }

    private @Nullable String profileName() {
        return jdbc.queryForObject("SELECT name FROM \"user\" WHERE id=?", String.class, target.getId());
    }

    private @Nullable String avatar() {
        return jdbc.queryForObject("SELECT avatar_url FROM \"user\" WHERE id=?", String.class, target.getId());
    }

    /** The inventory of the release before the one that adds {@code avatar_step}. */
    @PersonDataStores({"profile_step"})
    private record OlderCatalog(List<PersonDataContributor> contributors) implements PersonDataCatalog {}

    /** The inventory of the release that adds the live store {@code avatar_step}. */
    @PersonDataStores({"profile_step", "avatar_step"})
    private record NewerCatalog(List<PersonDataContributor> contributors) implements PersonDataCatalog {}
}
