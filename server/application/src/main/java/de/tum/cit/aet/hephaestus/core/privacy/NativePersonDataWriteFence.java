package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.List;
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
@WorkspaceAgnostic("Instance-wide exact native identity admission; content writers retain their workspace predicates")
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
    public boolean holdForUserWrite(long userId) {
        var identities = jdbc.query(
                "SELECT provider_id,native_id::text FROM \"user\" WHERE id=?",
                (rs, row) -> new PersonIdentity(rs.getLong(1), java.util.Objects.requireNonNull(rs.getString(2)), null),
                userId);
        return !identities.isEmpty() && holdIdentities(identities);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> holdForUserWrites(List<Long> userIds) {
        requireFreshControlReads();
        if (userIds.isEmpty()) return List.of();
        var users = jdbc.query(
                "SELECT id,provider_id,native_id::text FROM \"user\" WHERE id=ANY(?) ORDER BY id",
                (rs, row) -> new NativeUser(
                        rs.getLong(1),
                        new PersonIdentity(rs.getLong(2), java.util.Objects.requireNonNull(rs.getString(3)), null)),
                new org.springframework.jdbc.support.SqlArrayValue("bigint", userIds.toArray()));
        lock(users.stream().map(NativeUser::identity).toList(), true);
        return users.stream()
                .filter(user -> !suppression.isSuppressed(
                        user.identity().providerId(),
                        user.identity().subject(),
                        user.identity().teamId()))
                .map(NativeUser::userId)
                .toList();
    }

    private record NativeUser(long userId, PersonIdentity identity) {}

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

    private void lock(List<PersonIdentity> identities, boolean shared) {
        // Team variants share only a lock, not a suppression decision. This also covers Outline's
        // canonical provider-wide subject control. Hash collisions can only add serialization.
        // Sort the actual lock keys, not their inputs, to keep one lock order even on collisions.
        var keys = jdbc.query("""
                SELECT DISTINCT hashtextextended(jsonb_build_array(i."providerId",i.subject)::text,0) AS lock_key
                FROM jsonb_to_recordset(CAST(? AS jsonb)) AS i("providerId" bigint,subject text)
                ORDER BY lock_key
                """, (rs, row) -> rs.getLong(1), mapper.writeValueAsString(identities));
        String function = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
        for (long key : keys) {
            jdbc.query("SELECT " + function + "(?)", rs -> {}, key);
        }
    }
}
