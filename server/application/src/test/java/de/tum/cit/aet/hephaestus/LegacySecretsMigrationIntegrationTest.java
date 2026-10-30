package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeySealer;
import de.tum.cit.aet.hephaestus.core.database.DatabaseMigration;
import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemEncryptionKey;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

@Tag("integration")
class LegacySecretsMigrationIntegrationTest {
    private static final String SYSTEM_KEY = "0123456789abcdef0123456789abcdef";
    private static final String ACTIVE_KEY = "abcdef0123456789abcdef0123456789";
    private static final String PRIOR_KEY = "fedcba9876543210fedcba9876543210";
    private static @Nullable TestDatabase released;

    @BeforeAll
    static void prepare0950() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(name());
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            var target =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), target)) {
                var changes = liquibase.getDatabaseChangeLog().getChangeSets();
                int cut = 0;
                while (cut < changes.size() && !changes.get(cut).getId().equals("1793208610071-3")) cut++;
                assertThat(cut).isLessThan(changes.size());
                changes.subList(cut + 1, changes.size()).clear();
                liquibase.update(new Contexts("prod"));
            }
        }
        released = database;
    }

    @Test
    void shouldConvertPopulated0950SecretsWithoutChangingCredentialsOrSigningIdentity() throws Exception {
        var database = PostgreSQLTestContainer.copyDatabase(name(), Objects.requireNonNull(released));
        var jdbc = jdbc(database);
        byte[] privateKey = seed(jdbc);
        migrate(database, environment());
        var properties = Binder.get(environment()).bindOrCreate("hephaestus.security", SecurityProperties.class);
        var strings = new EncryptedStringConverter(new SystemEncryptionKey(properties, environment()));
        for (String query : new String[] {
            "SELECT client_secret FROM login_provider WHERE id=1001",
            "SELECT api_key FROM llm_connection WHERE id=1001",
            "SELECT api_key FROM workspace_llm_connection WHERE id=1001"
        }) {
            String encrypted = Objects.requireNonNull(jdbc.queryForObject(query, String.class));
            assertThat(encrypted).startsWith("ENC:");
            assertThat(strings.convertToEntityAttribute(encrypted)).isEqualTo("fixture-secret");
        }
        var active = new CredentialBundleConverter(ACTIVE_KEY, 8, PRIOR_KEY, 7, true);
        var credentials = jdbc.query(
                "SELECT id,credentials_encrypted,credentials_key_version,state FROM connection ORDER BY id",
                (rs, row) -> {
                    assertThat(rs.getInt(3)).isEqualTo(8);
                    assertThat(rs.getString(4)).isEqualTo("SUSPENDED");
                    return active.decrypt(Objects.requireNonNull(rs.getBytes(2)), context(rs.getLong(1)), rs.getInt(3));
                });
        assertThat(credentials)
                .containsExactly(
                        new BearerToken("token-1001", null),
                        new BearerToken("token-1002", null),
                        new BearerToken("token-1003", null));
        var sealer = new JwtSigningKeySealer(new SystemEncryptionKey(properties, environment()));
        var signedKeys = jdbc.query(
                "SELECT kid,private_key_pem,encryption_key_id,active FROM jwt_signing_key ORDER BY kid", (rs, row) -> {
                    assertThat(rs.getString(3)).isEqualTo(JwtSigningKeySealer.KEY_ID);
                    assertThat(sealer.unseal(Objects.requireNonNull(rs.getBytes(2))))
                            .isEqualTo(privateKey);
                    return rs.getString(1) + ":" + rs.getBoolean(4);
                });
        assertThat(signedKeys).containsExactly("fixture-active:true", "fixture-inactive:false");
        var ciphertexts =
                jdbc.query("SELECT credentials_encrypted FROM connection ORDER BY id", (rs, row) -> rs.getBytes(1));
        migrate(database, environment());
        assertThat(jdbc.query("SELECT credentials_encrypted FROM connection ORDER BY id", (rs, row) -> rs.getBytes(1)))
                .usingRecursiveComparison()
                .isEqualTo(ciphertexts);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-system-key",
                "missing-active-key",
                "wrong-system-key",
                "wrong-prior-key",
                "wrong-row",
                "unsupported-envelope"
            })
    void shouldRollBackEverySecretWhenAnEnvelopeCannotBeAuthenticated(String failure) throws Exception {
        var database = PostgreSQLTestContainer.copyDatabase(name(), Objects.requireNonNull(released));
        var jdbc = jdbc(database);
        seed(jdbc);
        var env = environment();
        switch (failure) {
            case "missing-system-key" -> env.setProperty("hephaestus.security.encryption-key", "");
            case "missing-active-key" -> env.setProperty("hephaestus.security.credential-encryption-key", "");
            case "wrong-system-key" -> env.setProperty("hephaestus.security.encryption-key", ACTIVE_KEY);
            case "wrong-prior-key" ->
                env.setProperty("hephaestus.security.prior-credential-encryption-key", ACTIVE_KEY);
            case "wrong-row" -> jdbc.update("UPDATE connection SET instance_key='another-row' WHERE id=1001");
            case "unsupported-envelope" ->
                jdbc.update(
                        "UPDATE connection SET credentials_encrypted=set_byte(credentials_encrypted,0,0) WHERE id=1001");
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> migrate(database, env))
                .hasStackTraceContaining("conversion failed")
                .hasMessageNotContainingAny("fixture-secret", "token-1001");
        assertThat(jdbc.queryForObject("SELECT client_secret FROM login_provider WHERE id=1001", String.class))
                .isEqualTo("fixture-secret");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM connection WHERE credentials_key_version IS NULL", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM jwt_signing_key WHERE encryption_key_id='v0-unsealed'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM databasechangelog WHERE id='1793394316000-1'", Integer.class))
                .isZero();
    }

    private static byte[] seed(JdbcTemplate jdbc) throws Exception {
        jdbc.update("""
                INSERT INTO workspace(id,slug,display_name,status,account_login,account_type)
                VALUES (1001,'secret-fixture','Secret fixture','ACTIVE','secret-fixture','ORG')
                """);
        jdbc.update("""
                INSERT INTO login_provider(id,registration_id,type,display_name,base_url,client_id,client_secret,scopes,enabled,seeded_from_env)
                VALUES (1001,'fixture','GITLAB','Fixture','https://gitlab.example','fixture','fixture-secret','read_user',false,false)
                """);
        jdbc.update("""
                INSERT INTO llm_connection(id,slug,display_name,base_url,api_protocol,api_key,created_at)
                VALUES (1001,'fixture','Fixture','https://llm.example','openai-completions','fixture-secret',now())
                """);
        jdbc.update("""
                INSERT INTO workspace_llm_connection(id,workspace_id,slug,display_name,base_url,api_protocol,api_key,created_at)
                VALUES (1001,1001,'fixture','Fixture','https://llm.example','openai-completions','fixture-secret',now())
                """);
        String[] keys = {SYSTEM_KEY, ACTIVE_KEY, PRIOR_KEY};
        for (int index = 0; index < keys.length; index++) {
            long id = 1001L + index;
            var converter = new CredentialBundleConverter(keys[index], false);
            jdbc.update(
                    """
                    INSERT INTO connection(id,workspace_id,kind,instance_key,state,config,credentials_encrypted,credentials_alg)
                    VALUES (?,1001,'GITLAB',?,'SUSPENDED','{"type":"GITLAB"}'::jsonb,?,'aesgcm-v2')
                    """, id, Long.toString(id), converter.encrypt(new BearerToken("token-" + id, null), context(id)));
        }
        var key = new ECKeyGenerator(Curve.P_256).generate();
        byte[] privateDer = key.toECPrivateKey().getEncoded();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(key.toECPublicKey().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        for (boolean active : new boolean[] {true, false}) {
            jdbc.update(
                    "INSERT INTO jwt_signing_key(kid,public_key_pem,private_key_pem,encryption_key_id,active) VALUES (?,?,?,'v0-unsealed',?)",
                    active ? "fixture-active" : "fixture-inactive",
                    publicPem,
                    privateDer,
                    active);
        }
        return privateDer;
    }

    private static EncryptionContext context(long id) {
        return new EncryptionContext(
                1001, IntegrationKind.GITLAB, Long.toString(id), "connection.credentials_encrypted");
    }

    private static MockEnvironment environment() {
        var environment = new MockEnvironment()
                .withProperty("hephaestus.security.encryption-key", SYSTEM_KEY)
                .withProperty("hephaestus.security.credential-encryption-key", ACTIVE_KEY)
                .withProperty("hephaestus.security.credential-encryption-key-version", "8")
                .withProperty("hephaestus.security.prior-credential-encryption-key", PRIOR_KEY)
                .withProperty("hephaestus.security.prior-credential-encryption-key-version", "7");
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static JdbcTemplate jdbc(TestDatabase database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
    }

    private static void migrate(TestDatabase database, MockEnvironment environment) throws Exception {
        var arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=prod,migrate",
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--spring.liquibase.contexts=prod",
                "--hephaestus.database.allow-insecure-remote=true",
                "--spring.main.banner-mode=off"));
        for (String key : List.of(
                "encryption-key",
                "credential-encryption-key",
                "credential-encryption-key-version",
                "prior-credential-encryption-key",
                "prior-credential-encryption-key-version")) {
            arguments.add("--hephaestus.security." + key + "=" + environment.getProperty("hephaestus.security." + key));
        }
        assertThat(DatabaseMigration.run(arguments.toArray(String[]::new), SecretMigrationConfiguration.class))
                .isZero();
    }

    private static String name() {
        return "secrets_" + UUID.randomUUID().toString().replace("-", "");
    }
}
