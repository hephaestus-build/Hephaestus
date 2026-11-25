package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Gives a database-tier lifecycle fixture the same signing capability as the trusted server. */
final class RowSweepAdmissionDatabaseFixture {

    private RowSweepAdmissionDatabaseFixture() {}

    static byte[] installKey(Connection owner) {
        var properties = TestSystemSecrets.properties(TestSystemSecrets.DEFAULT_KEY, null);
        RowSweepAdmissionKey.install(owner, properties);
        var sealed = jdbc(owner)
                .queryForObject(
                        "SELECT sealed_value FROM system_secret WHERE name = ?",
                        byte[].class,
                        RowSweepAdmission.KEY_NAME);
        return new SystemSecrets(properties)
                .open(
                        Objects.requireNonNull(sealed),
                        SecretBinding.of("system_secret.sealed_value", RowSweepAdmission.KEY_NAME));
    }

    static String admit(Connection application, byte[] key, RowSweepRule rule, long subject, @Nullable Long other)
            throws GeneralSecurityException {
        var app = jdbc(application);
        String payload = app.queryForObject("""
                SELECT concat_ws(':', 'person_row_sweep', ?, ?::bigint, COALESCE(?::text, '-'),
                                 pg_current_xact_id(), pg_backend_pid(), extract(epoch FROM transaction_timestamp()))
                """, String.class, rule.name(), subject, other);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        var proof = HexFormat.of()
                .formatHex(mac.doFinal(Objects.requireNonNull(payload).getBytes(StandardCharsets.UTF_8)));
        app.queryForObject("SELECT set_config('hephaestus.sweep_admission', ?, true)", String.class, proof);
        return proof;
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
