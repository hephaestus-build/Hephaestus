package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Exact provider-key closure, including disabled links. Cached actor ids are never ownership proof. */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Verified person requests intentionally span all workspaces")
public class ExactPersonIdentityResolver implements PersonIdentityResolver {

    private final JdbcTemplate jdbc;

    public ExactPersonIdentityResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersonScope resolve(@Nullable Long accountId, List<PersonIdentity> supplied) {
        if (accountId == null && supplied.isEmpty()) {
            throw invalid("Supply an account id or at least one exact provider identity");
        }
        if (supplied.size() > 32) {
            throw invalid("At most 32 provider identities are permitted");
        }
        if (accountId != null && (accountId <= 0 || !accountExists(accountId))) {
            throw invalid("The account id does not exist");
        }
        Set<PersonIdentity> identities = new LinkedHashSet<>(supplied);
        Set<Long> owners = new LinkedHashSet<>();
        if (accountId != null) {
            owners.add(accountId);
        }
        for (PersonIdentity identity : identities) {
            validateProvider(identity);
            owners.addAll(jdbc.query(
                    "SELECT DISTINCT account_id FROM identity_link "
                            + "WHERE provider_id = ? AND subject = ? AND team_id IS NOT DISTINCT FROM ?",
                    (rs, row) -> rs.getLong("account_id"),
                    identity.providerId(),
                    identity.subject(),
                    identity.teamId()));
        }
        if (owners.size() > 1) {
            throw conflict("The supplied identities belong to different accounts; correct the exact identity scope");
        }
        Long resolvedAccount = owners.stream().findFirst().orElse(null);
        if (resolvedAccount != null) {
            identities.addAll(jdbc.query(
                    "SELECT provider_id, subject, team_id FROM identity_link WHERE account_id = ?",
                    (rs, row) -> new PersonIdentity(
                            rs.getLong("provider_id"),
                            Objects.requireNonNull(rs.getString("subject")),
                            rs.getString("team_id")),
                    resolvedAccount));
        }
        List<Long> users = new ArrayList<>();
        for (PersonIdentity identity : identities) {
            String type = validateProvider(identity);
            if (type.equals("GITHUB") || type.equals("GITLAB")) {
                long nativeId = nativeScmId(identity.subject());
                List<Long> conflictingCaches = jdbc.query(
                        "SELECT il.id FROM identity_link il WHERE il.provider_id = ? AND il.subject = ? "
                                + "AND il.team_id IS NULL AND il.external_actor_id IS NOT NULL "
                                + "AND NOT EXISTS (SELECT 1 FROM \"user\" u WHERE u.id = il.external_actor_id "
                                + "AND u.provider_id = il.provider_id AND u.native_id = ?)",
                        (rs, row) -> rs.getLong("id"),
                        identity.providerId(),
                        identity.subject(),
                        nativeId);
                if (!conflictingCaches.isEmpty()) {
                    throw conflict(
                            "An SCM identity has a conflicting cached actor; repair the exact provider link first");
                }
                users.addAll(jdbc.query(
                        "SELECT id FROM \"user\" WHERE provider_id = ? AND native_id = ?",
                        (rs, row) -> rs.getLong("id"),
                        identity.providerId(),
                        nativeId));
            }
            // Check every link again, including closure additions and disabled links. An account
            // with an internally conflicting link must not widen an erasure request silently.
            List<Long> linked = jdbc.query(
                    "SELECT DISTINCT account_id FROM identity_link "
                            + "WHERE provider_id = ? AND subject = ? AND team_id IS NOT DISTINCT FROM ?",
                    (rs, row) -> rs.getLong("account_id"),
                    identity.providerId(),
                    identity.subject(),
                    identity.teamId());
            if (linked.stream().anyMatch(owner -> !owner.equals(resolvedAccount))) {
                throw conflict("A provider identity is linked to another account; resolve the link conflict first");
            }
        }
        List<PersonIdentity> ordered = identities.stream()
                .sorted(Comparator.comparingLong(PersonIdentity::providerId)
                        .thenComparing(PersonIdentity::subject)
                        .thenComparing(i -> Objects.requireNonNullElse(i.teamId(), "")))
                .toList();
        return new PersonScope(
                resolvedAccount, ordered, users.stream().distinct().sorted().toList());
    }

    private boolean accountExists(long accountId) {
        return !jdbc.queryForList("SELECT id FROM account WHERE id = ?", Long.class, accountId)
                .isEmpty();
    }

    private String validateProvider(PersonIdentity identity) {
        List<String> types = jdbc.query(
                "SELECT type FROM identity_provider WHERE id = ?",
                (rs, row) -> Objects.requireNonNull(rs.getString("type")),
                identity.providerId());
        if (types.size() != 1) {
            throw invalid("The provider instance id does not exist");
        }
        String type = types.getFirst();
        if (type.equals("SLACK")) {
            if (identity.teamId() == null) {
                throw invalid("A Slack identity requires its native Slack workspace id");
            }
        } else if (identity.teamId() != null) {
            throw invalid("Only a Slack identity accepts a native workspace id");
        }
        if (type.equals("GITHUB") || type.equals("GITLAB")) {
            nativeScmId(identity.subject());
        }
        return type;
    }

    private static long nativeScmId(String subject) {
        String maximum = Long.toString(Long.MAX_VALUE);
        if (!subject.matches("[1-9][0-9]{0,18}")
                || (subject.length() == maximum.length() && subject.compareTo(maximum) > 0)) {
            throw invalid("An SCM subject must be the exact positive native numeric user id");
        }
        return Long.parseLong(subject);
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
