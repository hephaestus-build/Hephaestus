package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@WorkspaceAgnostic("An instance-wide exact provider identity processing fence")
public class PersonSuppressionService implements PersonProcessingSuppression {
    private final JdbcTemplate jdbc;
    private final java.util.List<PersonSourceIdentityContributor> sourceOwners;

    public PersonSuppressionService(JdbcTemplate jdbc, java.util.List<PersonSourceIdentityContributor> sourceOwners) {
        var kinds = sourceOwners.stream()
                .flatMap(owner -> owner.artifactKinds().stream())
                .toList();
        if (kinds.size() != new java.util.HashSet<>(kinds).size()
                || !new java.util.HashSet<>(kinds)
                        .equals(java.util.Set.of(
                                "scm.issue", "scm.pull_request", "chat.conversation_thread", "docs.document"))) {
            throw new IllegalStateException(
                    "Each shipped artifact kind requires exactly one person source attribution owner");
        }
        this.jdbc = jdbc;
        this.sourceOwners = java.util.List.copyOf(sourceOwners);
    }

    public void suppress(PersonScope scope, UUID requestId) {
        for (PersonIdentity i : scope.identities()) {
            jdbc.update(
                    "INSERT INTO person_suppression(id, provider_id, subject, team_key) VALUES (?,?,?,?) ON CONFLICT(provider_id,subject,team_key) DO NOTHING",
                    UUID.randomUUID(),
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            UUID owner = jdbc.queryForObject(
                    "SELECT active_request_id FROM person_suppression WHERE provider_id=? AND subject=? AND team_key=? FOR UPDATE",
                    UUID.class,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            if (owner != null && !owner.equals(requestId))
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "An erasure request already owns an identity; resume that request");
            jdbc.update(
                    "UPDATE person_suppression SET active_request_id=? WHERE provider_id=? AND subject=? AND team_key=?",
                    requestId,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
        }
    }

    public void release(UUID requestId) {
        jdbc.update("UPDATE person_suppression SET active_request_id=NULL WHERE active_request_id=?", requestId);
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
                JOIN person_suppression s ON s.provider_id=p.id
                WHERE requested.id=? AND s.subject=?
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
    public boolean isUserSuppressed(long userId) {
        return jdbc
                .query(
                        "SELECT provider_id,native_id::text FROM \"user\" WHERE id=?",
                        (rs, row) -> new PersonIdentity(rs.getLong(1), Objects.requireNonNull(rs.getString(2)), null),
                        userId)
                .stream()
                .anyMatch(identity -> isSuppressed(identity.providerId(), identity.subject(), null));
    }

    @Override
    public boolean isArtifactSuppressed(long workspaceId, String artifactKind, long artifactId) {
        var owners = sourceOwners.stream()
                .filter(owner -> owner.artifactKinds().contains(artifactKind))
                .toList();
        if (owners.size() > 1) throw new IllegalStateException("Duplicate person source attribution owner");
        if (owners.isEmpty()) return false;
        var identities = owners.getFirst().identitiesForSource(workspaceId, artifactKind, artifactId);
        if (identities.isEmpty()) return false;
        return identities.stream()
                .anyMatch(identity -> isSuppressed(identity.providerId(), identity.subject(), identity.teamId()));
    }
}
