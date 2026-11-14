package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.modulith.NamedInterface;

/**
 * Seals each system secret again, named by its key and bound to its row (ADR 0104), once, while every role is stopped
 * for the 1.0 upgrade. It also keeps the key of erased-namespace reservations, which 0.x derived from the system key,
 * as a stored system secret, so the reservations survive a later system key rotation.
 *
 * <p>The earlier envelopes are read here only. No runtime path reads them.
 */
@NamedInterface("database-migration")
@WorkspaceAgnostic("Upgrade-time conversion of instance and workspace AI provider keys")
public class SystemSecretSealingChange implements CustomTaskChange {

    private static final String EARLIER_TEXT_PREFIX = "ENC:";
    private static final byte[] EARLIER_SIGNING_KEY_AAD =
            "system:jwt_signing_key.private_key_pem".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EARLIER_RESERVATION_PURPOSE =
            "hephaestus erased workspace namespace v1".getBytes(StandardCharsets.UTF_8);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private @Nullable Environment environment;

    /** {@link SystemSecretSealingConfiguration} gives the change the application's key configuration. */
    public void configure(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("System secret sealing requires a JDBC database");
        }
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM login_provider WHERE client_secret LIKE 'ENC:%')
                        OR EXISTS(SELECT 1 FROM llm_connection WHERE api_key LIKE 'ENC:%')
                        OR EXISTS(SELECT 1 FROM workspace_llm_connection WHERE api_key LIKE 'ENC:%')
                        OR EXISTS(SELECT 1 FROM jwt_signing_key WHERE get_byte(private_key_pem, 0) = 1)
                        OR EXISTS(SELECT 1 FROM workspace_slug_reservation WHERE slug LIKE '~%')
                    """, Boolean.class))) {
                // A new database: the server creates the reservation key when it first needs it.
                return;
            }
            Environment env = environment;
            if (env == null) {
                throw new CustomChangeException(
                        "Stored secrets need the system encryption key. Run the migration in the migrate run mode.");
            }
            SecurityProperties properties =
                    Binder.get(env).bindOrCreate("hephaestus.security", SecurityProperties.class);
            String systemKey = properties.encryptionKey();
            if (systemKey == null) {
                throw new CustomChangeException("Stored secrets need hephaestus.security.encryption-key to migrate."
                        + " Give the migration the system encryption key, then run it again.");
            }
            if (properties.priorEncryptionKey() != null) {
                throw new CustomChangeException("Configure only the existing system encryption key for this upgrade."
                        + " Rotate the key after the upgrade.");
            }
            SecretKey earlier = new SecretKeySpec(systemKey.getBytes(StandardCharsets.UTF_8), "AES");
            SystemSecrets secrets = new SystemSecrets(properties, env);
            sealText(jdbc, secrets, earlier, "login_provider", "client_secret", "registration_id");
            sealText(jdbc, secrets, earlier, "llm_connection", "api_key", "slug");
            sealText(jdbc, secrets, earlier, "workspace_llm_connection", "api_key", "workspace_id", "slug");
            sealSigningKeys(jdbc, secrets, earlier);
            byte[] reservationKey = hmac(earlier.getEncoded(), EARLIER_RESERVATION_PURPOSE);
            jdbc.update(
                    "INSERT INTO system_secret (name, sealed_value, created_at) VALUES (?, ?, now())"
                            + " ON CONFLICT (name) DO NOTHING",
                    "namespace-reservation",
                    secrets.seal(
                            reservationKey, SecretBinding.of("system_secret.sealed_value", "namespace-reservation")));
        } catch (CustomChangeException refusal) {
            throw refusal;
        } catch (Exception failure) {
            // The causes name no value: the statements bind their values, and the migration data source leaves
            // PostgreSQL's DETAIL line out.
            throw new CustomChangeException(
                    "Stored secrets could not be sealed again. Keep every role stopped, check that"
                            + " hephaestus.security.encryption-key is the key that sealed them, then run the migration"
                            + " again.",
                    failure);
        }
    }

    private static void sealText(
            JdbcTemplate jdbc,
            SystemSecrets secrets,
            SecretKey earlier,
            String table,
            String column,
            String... keyColumns) {
        String keys = String.join(", ", keyColumns);
        List<TextRow> rows = jdbc.query(
                "SELECT id, " + column + ", " + keys + " FROM " + table + " WHERE " + column
                        + " LIKE 'ENC:%' FOR UPDATE",
                (rs, index) -> {
                    List<String> rowKey = new ArrayList<>(keyColumns.length);
                    for (int i = 0; i < keyColumns.length; i++) rowKey.add(Objects.requireNonNull(rs.getString(3 + i)));
                    return new TextRow(rs.getLong(1), Objects.requireNonNull(rs.getString(2)), rowKey);
                });
        for (TextRow row : rows) {
            byte[] combined = Base64.getDecoder().decode(row.value().substring(EARLIER_TEXT_PREFIX.length()));
            byte[] plaintext = decrypt(earlier, combined, 0, null);
            jdbc.update(
                    "UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                    Base64.getEncoder()
                            .encodeToString(
                                    secrets.seal(plaintext, new SecretBinding(table + "." + column, row.key()))),
                    row.id());
        }
    }

    private static void sealSigningKeys(JdbcTemplate jdbc, SystemSecrets secrets, SecretKey earlier) {
        List<Map.Entry<String, byte[]>> rows = jdbc.query(
                "SELECT kid, private_key_pem FROM jwt_signing_key WHERE get_byte(private_key_pem, 0) = 1 FOR UPDATE",
                (rs, index) ->
                        Map.entry(Objects.requireNonNull(rs.getString(1)), Objects.requireNonNull(rs.getBytes(2))));
        for (Map.Entry<String, byte[]> row : rows) {
            byte[] privateKey = decrypt(earlier, row.getValue(), 1, EARLIER_SIGNING_KEY_AAD);
            jdbc.update(
                    "UPDATE jwt_signing_key SET private_key_pem = ? WHERE kid = ?",
                    secrets.seal(privateKey, SecretBinding.of("jwt_signing_key.private_key_pem", row.getKey())),
                    row.getKey());
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] envelope, int offset, byte @Nullable [] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOfRange(envelope, offset, offset + GCM_IV_LENGTH)));
            if (aad != null) cipher.updateAAD(aad);
            int start = offset + GCM_IV_LENGTH;
            return cipher.doFinal(envelope, start, envelope.length - start);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A stored secret did not open with the configured key", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private record TextRow(long id, String value, List<String> key) {}

    @Override
    public String getConfirmationMessage() {
        return "Sealed the system secrets with their key identifier and row";
    }

    @Override
    public void setUp() {}

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
