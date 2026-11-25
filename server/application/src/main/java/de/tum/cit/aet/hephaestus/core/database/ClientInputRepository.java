package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores result references for a workspace and its bound account. */
@Repository
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ClientInputRepository {
    private final JdbcClient jdbc;

    public record StoredReceipt(UUID resultId, UUID relatedId, boolean fingerprintMatches) {}

    public boolean tryLock(long workspace, long account, UUID key) {
        String lock = "client-input:" + workspace + ":" + account + ":" + key;
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", lock)
                .query(Boolean.class)
                .single();
    }

    public Optional<StoredReceipt> find(long workspace, long account, UUID key, byte[] fingerprint) {
        return jdbc.sql("""
            SELECT fingerprint,result_id,related_id FROM client_input
            WHERE workspace_id=:workspace AND account_id=:account AND idem_key=:key
            """)
                .param("workspace", workspace)
                .param("account", account)
                .param("key", key)
                .query((row, index) -> new StoredReceipt(
                        row.getObject("result_id", UUID.class),
                        row.getObject("related_id", UUID.class),
                        MessageDigest.isEqual(row.getBytes("fingerprint"), fingerprint)))
                .optional();
    }

    public void save(long workspace, long account, UUID key, byte[] fingerprint, UUID resultId, UUID relatedId) {
        jdbc.sql("""
            INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id)
            VALUES(:workspace,:account,:key,:fingerprint,:result,:related)
            """)
                .param("workspace", workspace)
                .param("account", account)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("result", resultId)
                .param("related", relatedId)
                .update();
    }
}
