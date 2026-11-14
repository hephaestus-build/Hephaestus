package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The 1.0 upgrade seals each value of the earlier envelopes again, bound to its row, and keeps the reservations. */
@Tag("database")
class SystemSecretSealingChangeDatabaseTest {

    private static final String CHANGELOG = "20261114152032-system-key-ids.xml";
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void shouldSealEachEarlierValueAgainAndKeepTheReservationKey() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("system_secret_sealing");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var sql = jdbc(database);
        sql.update(
                "INSERT INTO login_provider(registration_id,type,display_name,base_url,client_id,client_secret,scopes,"
                        + "enabled,seeded_from_env) VALUES ('gitlab','GITLAB','GitLab','https://gitlab.example.com','id',?,"
                        + "'read_user',true,false)",
                earlierText("client-secret"));
        sql.update(
                "INSERT INTO llm_connection(slug,display_name,base_url,api_protocol,api_key,created_at)"
                        + " VALUES ('primary','Primary','https://api.example.com','openai-completions',?,now())",
                earlierText("instance-key"));
        Long workspaceId = required(sql.queryForObject(
                "INSERT INTO workspace(account_login,account_type,display_name,slug,status)"
                        + " VALUES ('sealing','ORG','Sealing','sealing','ACTIVE') RETURNING id",
                Long.class));
        sql.update(
                "INSERT INTO workspace_llm_connection(workspace_id,slug,display_name,base_url,api_protocol,api_key,"
                        + "created_at) VALUES (?,'own','Own','https://api.example.com','openai-completions',?,now())",
                workspaceId,
                earlierText("workspace-key"));
        byte[] privateKey = "pkcs8-bytes".getBytes(StandardCharsets.UTF_8);
        sql.update(
                "INSERT INTO jwt_signing_key(kid,public_key_pem,private_key_pem,encryption_key_id)"
                        + " VALUES ('kid-1','pem',?,'aesgcm-system-v1')",
                earlierSigningKey(privateKey));

        migrate(database, KEY);

        SystemSecrets secrets = systemSecrets(KEY, "test");
        assertThat(secrets.openText(
                        required(sql.queryForObject(
                                "SELECT client_secret FROM login_provider WHERE registration_id='gitlab'",
                                String.class)),
                        SecretBinding.of("login_provider.client_secret", "gitlab")))
                .isEqualTo("client-secret");
        assertThat(secrets.openText(
                        required(sql.queryForObject(
                                "SELECT api_key FROM llm_connection WHERE slug='primary'", String.class)),
                        SecretBinding.of("llm_connection.api_key", "primary")))
                .isEqualTo("instance-key");
        assertThat(secrets.openText(
                        required(sql.queryForObject(
                                "SELECT api_key FROM workspace_llm_connection WHERE slug='own'", String.class)),
                        SecretBinding.of("workspace_llm_connection.api_key", workspaceId.toString(), "own")))
                .isEqualTo("workspace-key");
        assertThat(secrets.open(
                        required(sql.queryForObject(
                                "SELECT private_key_pem FROM jwt_signing_key WHERE kid='kid-1'", byte[].class)),
                        SecretBinding.of("jwt_signing_key.private_key_pem", "kid-1")))
                .isEqualTo(privateKey);
        byte[] reservationKey = secrets.open(
                required(sql.queryForObject(
                        "SELECT sealed_value FROM system_secret WHERE name='namespace-reservation'", byte[].class)),
                SecretBinding.of("system_secret.sealed_value", "namespace-reservation"));
        assertThat(reservationKey).isEqualTo(earlierReservationKey());
        assertThat(sql.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_name='jwt_signing_key'"
                                + " AND column_name='encryption_key_id'",
                        Integer.class))
                .isZero();
    }

    @Test
    void shouldRefuseAKeyThatDidNotSealTheValuesAndWriteNothing() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("system_secret_sealing_wrong_key");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var sql = jdbc(database);
        String earlier = earlierText("instance-key");
        sql.update(
                "INSERT INTO llm_connection(slug,display_name,base_url,api_protocol,api_key,created_at)"
                        + " VALUES ('primary','Primary','https://api.example.com','openai-completions',?,now())",
                earlier);

        assertThatThrownBy(() -> migrate(database, "fedcba9876543210fedcba9876543210"))
                .hasStackTraceContaining("is the key that sealed them");

        assertThat(sql.queryForObject("SELECT api_key FROM llm_connection WHERE slug='primary'", String.class))
                .isEqualTo(earlier);
    }

    /** The migrate run mode, as the migration Job starts it, so the key reaches the change set as in production. */
    private static void migrate(PostgreSQLTestContainer.TestDatabase database, String key) {
        int status = DatabaseMigration.run(new String[] {
            "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
            "--spring.liquibase.contexts=prod",
            "--spring.datasource.url=" + database.jdbcUrl(),
            "--spring.datasource.username=" + database.username(),
            "--spring.datasource.password=" + database.password(),
            "--hephaestus.security.encryption-key=" + key,
            "--spring.main.banner-mode=off"
        });
        assertThat(status).isZero();
    }

    private static <T> T required(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    private static JdbcTemplate jdbc(PostgreSQLTestContainer.TestDatabase database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
    }

    /** The text envelope of the earlier release: {@code ENC:} and Base64 of nonce and ciphertext, without AAD. */
    private static String earlierText(String plaintext) throws Exception {
        return "ENC:" + Base64.getEncoder().encodeToString(seal(plaintext.getBytes(StandardCharsets.UTF_8), null));
    }

    /** The signing key envelope of the earlier release: format byte 0x01, nonce and ciphertext, with a fixed AAD. */
    private static byte[] earlierSigningKey(byte[] privateKey) throws Exception {
        byte[] sealed = seal(privateKey, "system:jwt_signing_key.private_key_pem".getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(1 + sealed.length)
                .put((byte) 0x01)
                .put(sealed)
                .array();
    }

    private static byte[] seal(byte[] plaintext, byte @Nullable [] aad) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce)
                .put(ciphertext)
                .array();
    }

    /** The reservation key that the earlier release derived from the system key. */
    private static byte[] earlierReservationKey() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal("hephaestus erased workspace namespace v1".getBytes(StandardCharsets.UTF_8));
    }
}
