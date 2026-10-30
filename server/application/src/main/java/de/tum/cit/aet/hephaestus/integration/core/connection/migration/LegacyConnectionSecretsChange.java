package de.tum.cit.aet.hephaestus.integration.core.connection.migration;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;

/** Repairs credentials whose released backfill omitted the encryption key version. */
public class LegacyConnectionSecretsChange implements CustomTaskChange {
    private @Nullable Environment environment;

    public void configure(Environment environment) {
        this.environment = environment;
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace") // SQL and JSON causes can contain secrets.
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Connection secret conversion requires a JDBC database");
        }
        var jdbc = connection.getUnderlyingConnection();
        try {
            try (var statement = jdbc.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT EXISTS(SELECT 1 FROM connection WHERE credentials_encrypted IS NOT NULL AND credentials_key_version IS NULL)")) {
                rows.next();
                if (!rows.getBoolean(1)) return;
            }
            var env = Objects.requireNonNull(environment, "Use the application migrate run mode for secret conversion");
            var properties = Binder.get(env).bindOrCreate("hephaestus.security", SecurityProperties.class);
            var converter = new CredentialBundleConverter(
                    properties, env, JsonMapper.builder().findAndAddModules().build());
            var candidates = new ArrayList<CredentialBundleConverter>();
            if (properties.credentialEncryptionKey() != null)
                candidates.add(new CredentialBundleConverter(properties.credentialEncryptionKey(), false));
            if (properties.priorCredentialEncryptionKey() != null)
                candidates.add(new CredentialBundleConverter(properties.priorCredentialEncryptionKey(), false));
            // The released workspace backfill used the system key and wrote no credential key version.
            if (properties.encryptionKey() != null)
                candidates.add(new CredentialBundleConverter(properties.encryptionKey(), false));
            try (var statement = jdbc.createStatement();
                    var rows = statement.executeQuery("""
                        SELECT id,workspace_id,kind,instance_key,credentials_encrypted FROM connection
                        WHERE credentials_encrypted IS NOT NULL AND credentials_key_version IS NULL
                        """);
                    var update = jdbc.prepareStatement("""
                        UPDATE connection SET credentials_encrypted=?,credentials_alg=?,credentials_key_version=?,version=version+1
                        WHERE id=?
                        """)) {
                while (rows.next()) {
                    var context = new EncryptionContext(
                            rows.getLong(2),
                            IntegrationKind.valueOf(Objects.requireNonNull(rows.getString(3))),
                            rows.getString(4),
                            "connection.credentials_encrypted");
                    var bundle = decrypt(Objects.requireNonNull(rows.getBytes(5)), context, candidates);
                    update.setBytes(1, converter.encrypt(bundle, context));
                    update.setString(2, CredentialBundleConverter.ALGORITHM_TAG);
                    update.setInt(3, converter.activeKeyVersion());
                    update.setLong(4, rows.getLong(1));
                    update.executeUpdate();
                }
            }
        } catch (Exception exception) {
            throw new CustomChangeException(
                    "Connection credential conversion failed. Keep writers stopped, verify the active, prior and system keys, then retry the migration.");
        }
    }

    private static CredentialBundle decrypt(
            byte[] blob, EncryptionContext context, List<CredentialBundleConverter> candidates) {
        for (var candidate : candidates) {
            try {
                return candidate.decrypt(blob, context);
            } catch (EncryptionException exception) {
                // Only authenticated v2 envelopes qualify; never reinterpret a failed decryption as plaintext.
            }
        }
        throw new EncryptionException("No configured key authenticates a legacy connection credential");
    }

    @Override
    public String getConfirmationMessage() {
        return "Encrypted legacy connection credentials with the active key version";
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
