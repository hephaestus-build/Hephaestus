package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL transaction locks close the admission/erasure race across replicas.
 * https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS
 */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic(
        "Instance-wide exact native identity admission; content writers retain their workspace" + " predicates")
public class NativePersonDataWriteFence implements PersonDataWriteFence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonProcessingSuppression suppression;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdForWrite(List<PersonIdentity> identities) {
        return holdIdentities(identities);
    }

    private boolean holdIdentities(List<PersonIdentity> identities) {
        requireFreshControlReads();
        lock(identities, true);
        for (var identity : identities) {
            if (suppression.isSuppressed(identity.providerId(), identity.subject(), identity.teamId())) {
                return false;
            }
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdForAccountWrite(long accountId) {
        var identities = jdbc.query(
                "SELECT i.provider_id,i.provider_user_key,NULLIF(i.team_key,'') FROM identity i JOIN"
                        + " account a ON a.id=i.account_id WHERE i.account_id=? AND a.status IN"
                        + " ('ACTIVE','UNCLAIMED')",
                (rs, row) ->
                        new PersonIdentity(rs.getLong(1), Objects.requireNonNull(rs.getString(2)), rs.getString(3)),
                accountId);
        if (identities.isEmpty() || !holdIdentities(identities)) return false;
        return jdbc
                .query(
                        "SELECT status IN ('ACTIVE','UNCLAIMED') FROM account WHERE id=? FOR SHARE",
                        (rs, row) -> rs.getBoolean(1),
                        accountId)
                .stream()
                .anyMatch(Boolean::booleanValue);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForErasure(List<PersonIdentity> identities) {
        requireFreshControlReads();
        lock(identities, false);
    }

    private void requireFreshControlReads() {
        // A repeatable-read snapshot can predate a control committed while this transaction waits
        // for the native lock. Never admit a writer from that old snapshot.
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalStateException("Person data admission requires a READ_COMMITTED transaction");
        }
    }

    private record NativeKey(String providerType, String providerOrigin, String subject) {}

    private void lock(List<PersonIdentity> identities, boolean shared) {
        // Team variants share only a lock, not a suppression decision. This also covers Outline's
        // canonical provider-wide subject control. Hash collisions can only add serialization.
        // Sort the actual lock keys, not their inputs, to keep one lock order even on collisions.
        // Equivalent provider rows share the same native lock, including rows registered after
        // preview. Otherwise a new alias could commit while erasure checks the old provider row.
        var nativeKeys = jdbc.query(
                """
                SELECT p.type,p.server_url,i.subject
                FROM jsonb_to_recordset(CAST(? AS jsonb)) AS i("providerId" bigint,subject text)
                JOIN identity_provider p ON p.id=i."providerId"
                """,
                (rs, row) -> new NativeKey(
                        Objects.requireNonNull(rs.getString(1)),
                        ScmOrigin.of(rs.getString(2))
                                .orElseThrow(() -> new IllegalStateException(
                                        "Native admission requires" + " a valid provider" + " origin")),
                        Objects.requireNonNull(rs.getString(3))),
                mapper.writeValueAsString(identities));
        if (nativeKeys.size() != identities.size())
            throw new IllegalStateException("Native admission requires existing exact provider keys");
        var keys = jdbc.query("""
            SELECT DISTINCT hashtextextended(
                jsonb_build_array(i."providerType",i."providerOrigin",i.subject)::text,0) AS lock_key
            FROM jsonb_to_recordset(CAST(? AS jsonb))
                AS i("providerType" text,"providerOrigin" text,subject text)
            ORDER BY lock_key
            """, (rs, row) -> rs.getLong(1), mapper.writeValueAsString(nativeKeys));
        String function = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
        for (long key : keys) {
            jdbc.query("SELECT " + function + "(?)", rs -> {}, key);
        }
    }
}
