package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

final class RowSweepAdmissionKey {

    private static final int KEY_BYTES = 32;
    private static final byte INNER = 0x36;
    private static final byte OUTER = 0x5c;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SecretBinding BINDING =
            SecretBinding.of("system_secret.sealed_value", RowSweepAdmission.KEY_NAME);

    private RowSweepAdmissionKey() {}

    /** Installs the verifier and sealed signing key together, before the migration releases its lock. */
    static void install(Connection session, SecurityProperties properties) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(session, true));
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass('lifecycle_private.sweep_key') IS NOT NULL", Boolean.class))) {
            return;
        }
        var existingPad = jdbc
                .query(
                        "SELECT inner_pad FROM lifecycle_private.sweep_key",
                        (rs, row) -> Objects.requireNonNull(rs.getBytes(1)))
                .stream()
                .findFirst()
                .orElse(null);
        if (existingPad != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT FROM public.system_secret WHERE name = ?)",
                        Boolean.class,
                        RowSweepAdmission.KEY_NAME))) {
            // The running server rotates stored values, including this sealed copy.
            return;
        }
        var secrets = new SystemSecrets(properties);
        var key = recoverKey(existingPad);
        verifyExistingSealing(jdbc, secrets);
        jdbc.update("""
                WITH installed AS (
                    INSERT INTO lifecycle_private.sweep_key (singleton, inner_pad, outer_pad)
                    VALUES (true, ?, ?)
                    ON CONFLICT DO NOTHING
                )
                INSERT INTO public.system_secret (name, sealed_value, created_at)
                VALUES (?, ?, now()) ON CONFLICT (name) DO UPDATE SET sealed_value = EXCLUDED.sealed_value
                """, pad(key, INNER), pad(key, OUTER), RowSweepAdmission.KEY_NAME, secrets.seal(key, BINDING));
    }

    private static void verifyExistingSealing(JdbcTemplate jdbc, SystemSecrets secrets) {
        try {
            jdbc.query(
                    "SELECT name, sealed_value FROM public.system_secret WHERE name <> ?",
                    rs -> {
                        secrets.open(
                                Objects.requireNonNull(rs.getBytes(2)),
                                SecretBinding.of(
                                        "system_secret.sealed_value", Objects.requireNonNull(rs.getString(1))));
                    },
                    RowSweepAdmission.KEY_NAME);
            jdbc.query("SELECT kid, private_key_pem FROM public.jwt_signing_key WHERE active", rs -> {
                secrets.open(
                        Objects.requireNonNull(rs.getBytes(2)),
                        SecretBinding.of("jwt_signing_key.private_key_pem", Objects.requireNonNull(rs.getString(1))));
            });
        } catch (EncryptionException e) {
            throw new IllegalStateException(
                    "The configured system key does not open existing values. "
                            + "Restore the system key and run the database migration again.",
                    e);
        }
    }

    private static byte[] recoverKey(byte @Nullable [] innerPad) {
        byte[] key = new byte[KEY_BYTES];
        if (innerPad == null) {
            RANDOM.nextBytes(key);
        } else {
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (innerPad[i] ^ INNER);
            }
        }
        return key;
    }

    private static byte[] pad(byte[] key, byte value) {
        byte[] pad = new byte[64];
        Arrays.fill(pad, value);
        for (int i = 0; i < key.length; i++) {
            pad[i] ^= key[i];
        }
        return pad;
    }
}
