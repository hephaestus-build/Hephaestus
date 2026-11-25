package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.auth.oauth.VerifiedAccountClaim;
import de.tum.cit.aet.hephaestus.core.security.StoredSystemSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lets the database recognize a lifecycle decision from the server that holds the transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class RowSweepAdmission {

    public static final String KEY_NAME = "owner-sweep-admission";

    private final JdbcTemplate jdbc;
    private final StoredSystemSecrets secrets;

    public RowSweepAdmission(JdbcTemplate jdbc, StoredSystemSecrets secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    /**
     * Call after the workspace purge guards pass, during erasure replay with writers stopped, or when a person's
     * erasure ends their personal space after it suspended the space.
     */
    public void admitWorkspacePurge(long workspaceId) {
        admit(RowSweepRule.WORKSPACE_PURGE, workspaceId, null);
    }

    /** Call after a verified claim locks both accounts. */
    public void admitAccountAbsorption(VerifiedAccountClaim claim) {
        admit(RowSweepRule.ACCOUNT_ABSORB, claim.getUnclaimedAccountId(), claim.getClaimedAccountId());
    }

    private void admit(RowSweepRule rule, long subjectId, @Nullable Long otherId) {
        byte[] key = secrets.find(KEY_NAME)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Owner sweep admission key is missing. Run the database migration before starting the server."));
        var transaction = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT concat_ws(':', pg_current_xact_id(), pg_backend_pid(),
                                 extract(epoch FROM transaction_timestamp()))
                """, String.class));
        String payload = "person_row_sweep:" + rule.name() + ":" + subjectId + ":" + Objects.toString(otherId, "-")
                + ":" + transaction;
        jdbc.queryForObject(
                "SELECT set_config('hephaestus.sweep_admission', ?, true)", String.class, sign(key, payload));
    }

    private static String sign(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
