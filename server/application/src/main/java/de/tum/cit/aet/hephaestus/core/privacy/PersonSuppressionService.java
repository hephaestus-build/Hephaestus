package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("An instance-wide exact provider identity processing fence")
public class PersonSuppressionService implements PersonProcessingSuppression {
    private final JdbcTemplate jdbc;

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
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM person_suppression WHERE provider_id=? AND subject=? AND team_key=?)",
                Boolean.class,
                providerId,
                subject,
                Objects.requireNonNullElse(teamId, "")));
    }

    @Override
    public boolean isUserSuppressed(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM person_suppression s JOIN \"user\" u ON u.provider_id=s.provider_id AND u.native_id::text=s.subject WHERE u.id=? AND s.team_key='')",
                Boolean.class,
                userId));
    }
}
