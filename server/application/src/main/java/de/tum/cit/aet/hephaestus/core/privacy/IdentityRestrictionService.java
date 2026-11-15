package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProviderInstanceRegistered;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@WorkspaceAgnostic("An instance-wide exact provider identity processing fence")
public class IdentityRestrictionService implements PersonProcessingSuppression {
    private final JdbcTemplate jdbc;
    private final PersonDataCopyFence copies;
    private final List<PersonSourceIdentityContributor> sourceOwners;

    public IdentityRestrictionService(
            JdbcTemplate jdbc, List<PersonSourceIdentityContributor> sourceOwners, PersonDataCopyFence copies) {
        var kinds = sourceOwners.stream()
                .flatMap(owner -> owner.itemKinds().stream())
                .toList();
        if (kinds.size() != new HashSet<>(kinds).size()) {
            throw new IllegalStateException("Each item kind requires exactly one person source attribution owner");
        }
        this.jdbc = jdbc;
        this.copies = copies;
        this.sourceOwners = List.copyOf(sourceOwners);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProviderInstanceRegistered(PersonProviderInstanceRegistered event) {
        copies.holdForCapture();
        inheritProviderControls(jdbc, event.providerId());
    }

    static void inheritProviderControls(JdbcOperations jdbc, long providerId) {
        var instance = jdbc.query(
                "SELECT type,server_url FROM identity_provider WHERE id=?",
                (rs, row) -> new ProviderInstance(
                        Objects.requireNonNull(rs.getString(1)),
                        ScmOrigin.of(rs.getString(2))
                                .orElseThrow(() -> new IllegalStateException(
                                        "Provider controls require" + " an exact valid" + " origin"))),
                providerId);
        if (instance.size() != 1) throw new IllegalStateException("The exact provider namespace does not exist");
        var target = instance.getFirst();
        var equivalent = jdbc
                .query(
                        """
                        SELECT DISTINCT p.id,p.server_url FROM identity_provider p
                        JOIN identity_restriction s ON s.provider_id=p.id
                        WHERE p.type=? AND p.id<>?
                        """,
                        (rs, row) -> new ProviderAlias(rs.getLong(1), ScmOrigin.of(rs.getString(2))),
                        target.type(),
                        providerId)
                .stream()
                .filter(alias -> alias.origin().filter(target.origin()::equals).isPresent())
                .toList();
        for (var alias : equivalent) {
            jdbc.update("""
                INSERT INTO identity_restriction(id,provider_id,provider_user_key,team_key,kind,active_request_id)
                SELECT gen_random_uuid(),?,provider_user_key,team_key,kind,active_request_id
                FROM identity_restriction WHERE provider_id=?
                ON CONFLICT(provider_id,provider_user_key,team_key,kind) DO NOTHING
                """, providerId, alias.id());
        }
    }

    private record ProviderInstance(String type, String origin) {}

    private record ProviderAlias(long id, Optional<String> origin) {}

    public void suppress(PersonScope scope, UUID requestId) {
        for (PersonIdentity i : scope.identities()) {
            jdbc.update(
                    "INSERT INTO identity_restriction(id, provider_id, provider_user_key, team_key,"
                            + " kind) VALUES (?,?,?,?,'ERASED') ON"
                            + " CONFLICT(provider_id,provider_user_key,team_key,kind) DO NOTHING",
                    UUID.randomUUID(),
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            UUID owner = jdbc.queryForObject(
                    "SELECT active_request_id FROM identity_restriction WHERE provider_id=?"
                            + " AND provider_user_key=? AND team_key=? AND kind='ERASED' FOR"
                            + " UPDATE",
                    UUID.class,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            if (owner != null && !owner.equals(requestId))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "An erasure request already owns an identity. Resume that request.");
            jdbc.update(
                    "UPDATE identity_restriction SET active_request_id=? WHERE provider_id=? AND"
                            + " provider_user_key=? AND team_key=? AND kind='ERASED'",
                    requestId,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
        }
    }

    public void release(UUID requestId) {
        jdbc.update("UPDATE identity_restriction SET active_request_id=NULL WHERE active_request_id=?", requestId);
    }

    @Override
    public boolean isSuppressed(long providerId, String subject, @Nullable String teamId) {
        // A new provider row must not reset a permanent native control. Compare exact network
        // origins with the same parser as identity resolution, never with URL text or display data.
        return jdbc
                .query(
                        """
                        SELECT p.server_url,requested.server_url
                        FROM identity_provider requested
                        JOIN identity_provider p ON p.type=requested.type
                        JOIN identity_restriction s ON s.provider_id=p.id
                        WHERE requested.id=? AND s.provider_user_key=? AND s.kind='ERASED'
                          AND (s.team_key=? OR (p.type='OUTLINE' AND s.team_key=''))
                        """,
                        (rs, row) -> {
                            var requestedOrigin = ScmOrigin.of(rs.getString(2));
                            return ScmOrigin.of(rs.getString(1))
                                    .filter(origin -> requestedOrigin
                                            .filter(origin::equals)
                                            .isPresent())
                                    .isPresent();
                        },
                        providerId,
                        subject,
                        Objects.requireNonNullElse(teamId, ""))
                .stream()
                .anyMatch(Boolean::booleanValue);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isReviewJobSuppressed(UUID jobId) {
        return reviewJobsSuppressed(List.of(jobId)).contains(jobId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Set<UUID> suppressedReviewJobs(Collection<UUID> jobIds) {
        return reviewJobsSuppressed(jobIds);
    }

    private Set<UUID> reviewJobsSuppressed(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.query(
                """
            SELECT job.id FROM unnest(CAST(? AS uuid[])) AS job(id)
            WHERE EXISTS(SELECT 1 FROM person_data_request
              WHERE state IN ('ERASING','FAILED') AND selections_json IS NOT NULL
                AND ((selections_json::jsonb)->'review_job'->'rows' @> jsonb_build_array(
                        jsonb_build_object('columns', jsonb_build_object('id', job.id::text)))
                  OR (selections_json::jsonb)->'person_evidence_copy'->'rows' @> jsonb_build_array(
                        jsonb_build_object('columns', jsonb_build_object('id', job.id::text)))))
            """,
                (rs, row) -> rs.getObject(1, UUID.class),
                new SqlArrayValue("uuid", jobIds.stream().distinct().toArray())));
    }

    @Override
    public boolean isAccountSuppressed(long accountId) {
        return suppressedAccountIds(Set.of(accountId)).contains(accountId);
    }

    @Override
    public Set<Long> suppressedAccountIds(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) return Set.of();
        return new NamedParameterJdbcTemplate(jdbc)
                        .query("""
            SELECT a.id, p.server_url, requested.server_url, a.status IN ('DELETING','DELETED') AS deleting
            FROM account a
            LEFT JOIN identity i ON i.account_id=a.id
            LEFT JOIN identity_provider requested ON requested.id=i.provider_id
            LEFT JOIN identity_provider p ON p.type=requested.type
            LEFT JOIN identity_restriction s ON s.provider_id=p.id
              AND s.provider_user_key=i.provider_user_key AND s.kind='ERASED'
              AND (s.team_key=i.team_key OR (p.type='OUTLINE' AND s.team_key=''))
            WHERE a.id IN (:accountIds) AND (a.status IN ('DELETING','DELETED') OR s.id IS NOT NULL)
            """, Map.of("accountIds", accountIds), (rs, row) -> {
                            var requestedOrigin = ScmOrigin.of(rs.getString(3));
                            boolean suppressed = rs.getBoolean(4)
                                    || ScmOrigin.of(rs.getString(2))
                                            .filter(origin -> requestedOrigin
                                                    .filter(origin::equals)
                                                    .isPresent())
                                            .isPresent();
                            return new AccountRestriction(rs.getLong(1), suppressed);
                        })
                        .stream()
                        .filter(AccountRestriction::suppressed)
                        .map(AccountRestriction::accountId)
                        .collect(Collectors.toUnmodifiableSet());
    }

    private record AccountRestriction(long accountId, boolean suppressed) {}

    @Override
    public boolean isSourceSuppressed(long workspaceId, String itemKind, long sourceId) {
        var owners = sourceOwners.stream()
                .filter(owner -> owner.itemKinds().contains(itemKind))
                .toList();
        if (owners.size() > 1) throw new IllegalStateException("Duplicate person source attribution owner");
        if (owners.isEmpty()) throw new IllegalStateException("No person source attribution owner is available");
        var identities = owners.getFirst().identitiesForSource(workspaceId, itemKind, sourceId);
        if (identities.isEmpty()) return false;
        return identities.stream()
                .anyMatch(identity -> isSuppressed(identity.providerId(), identity.subject(), identity.teamId()));
    }
}
