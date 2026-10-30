package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeySealer;
import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemEncryptionKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.Objects;
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

/** Converts legacy secrets once, in the changelog transaction, before runtime readers become strict. */
@NamedInterface("database-migration")
public class LegacySecretsChange implements CustomTaskChange {

    private @Nullable Environment environment;

    public void configure(Environment environment) {
        this.environment = environment;
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace") // SQL and JSON causes can contain secrets.
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Secret conversion requires a JDBC database");
        }
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM login_provider WHERE client_secret NOT LIKE 'ENC:%')
                    OR EXISTS(SELECT 1 FROM llm_connection WHERE api_key NOT LIKE 'ENC:%')
                    OR EXISTS(SELECT 1 FROM workspace_llm_connection WHERE api_key NOT LIKE 'ENC:%')
                    OR EXISTS(SELECT 1 FROM jwt_signing_key WHERE encryption_key_id='v0-unsealed')
                    """, Boolean.class))) return;
            var env = Objects.requireNonNull(environment, "Use the application migrate run mode for secret conversion");
            var properties = Binder.get(env).bindOrCreate("hephaestus.security", SecurityProperties.class);
            var systemKey = new SystemEncryptionKey(properties, env);
            var strings = new EncryptedStringConverter(systemKey);
            for (var column : List.of(
                    new SecretColumn("login_provider", "client_secret"),
                    new SecretColumn("llm_connection", "api_key"),
                    new SecretColumn("workspace_llm_connection", "api_key"))) {
                var rows = jdbc.query(
                        "SELECT id," + column.column() + " FROM " + column.table() + " WHERE " + column.column()
                                + " NOT LIKE 'ENC:%' FOR UPDATE",
                        (rs, row) -> new StringSecret(rs.getLong(1), Objects.requireNonNull(rs.getString(2))));
                for (var row : rows) {
                    jdbc.update(
                            "UPDATE " + column.table() + " SET " + column.column() + "=? WHERE id=?",
                            strings.convertToDatabaseColumn(row.value()),
                            row.id());
                }
            }
            var sealer = new JwtSigningKeySealer(systemKey);
            jdbc.query(
                    "SELECT kid,private_key_pem FROM jwt_signing_key WHERE encryption_key_id='v0-unsealed' FOR UPDATE",
                    rs -> {
                        byte[] privateDer = Objects.requireNonNull(rs.getBytes(2));
                        try {
                            KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
                        } catch (GeneralSecurityException exception) {
                            throw new EncryptionException("Invalid legacy JWT signing key", exception);
                        }
                        jdbc.update(
                                "UPDATE jwt_signing_key SET private_key_pem=?,encryption_key_id=? WHERE kid=?",
                                sealer.seal(privateDer),
                                sealer.keyId(),
                                rs.getString(1));
                    });
        } catch (Exception exception) {
            throw new CustomChangeException(
                    "Secret conversion failed. Keep writers stopped, verify the system, active and prior credential keys, then retry the migration.");
        }
    }

    private record SecretColumn(String table, String column) {}

    private record StringSecret(long id, String value) {}

    @Override
    public String getConfirmationMessage() {
        return "Converted legacy secrets to encrypted storage";
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
