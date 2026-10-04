package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
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

    private final List<PersonSourceIdentityContributor> sourceOwners;

    public ExactPersonIdentityResolver(JdbcTemplate jdbc, List<PersonSourceIdentityContributor> sourceOwners) {
        this.jdbc = jdbc;
        this.sourceOwners = List.copyOf(sourceOwners);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Provider> providers() {
        return providerRows();
    }

    private List<Provider> providerRows() {
        return jdbc.query(
                "SELECT id,type,server_url FROM identity_provider ORDER BY type,server_url,id",
                (rs, row) -> new Provider(
                        rs.getLong(1),
                        Objects.requireNonNull(rs.getString(2)),
                        Objects.requireNonNull(rs.getString(3))));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersonScope resolve(@Nullable Long accountId, List<PersonIdentity> supplied) {
        if (accountId == null && supplied.isEmpty()) {
            throw invalid("Supply an account id or at least one exact provider identity");
        }
        if (supplied.size() > 32) {
            throw invalid("Supply no more than 32 provider identities");
        }
        if (accountId != null && (accountId <= 0 || !accountExists(accountId))) {
            throw invalid("The account id does not exist");
        }
        Set<PersonIdentity> identities = new LinkedHashSet<>(supplied);
        includeEquivalentProviders(identities);
        Set<Long> owners = new LinkedHashSet<>();
        if (accountId != null) {
            owners.add(accountId);
        }
        for (PersonIdentity identity : identities) {
            owners.addAll(linkedOwners(identity));
        }
        if (owners.size() > 1) {
            throw conflict("The supplied identities belong to different accounts. Correct the exact identity scope.");
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
        includeEquivalentProviders(identities);
        // A mirror is keyed by instance and native user, not by the OAuth workspace.
        // Normalize after account closure so account-only requests install the same control.
        for (PersonIdentity identity : List.copyOf(identities)) {
            if (validateProvider(identity).equals("OUTLINE"))
                identities.add(new PersonIdentity(identity.providerId(), identity.subject(), null));
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
                            "An SCM identity has a conflicting cached actor. Repair the exact provider link first.");
                }
                users.addAll(jdbc.query(
                        "SELECT id FROM \"user\" WHERE provider_id = ? AND native_id = ?",
                        (rs, row) -> rs.getLong("id"),
                        identity.providerId(),
                        nativeId));
            }
            // Check every link again, including closure additions and disabled links. An account
            // with an internally conflicting link must not widen an erasure request silently.
            List<Long> linked = linkedOwners(identity);
            if (linked.stream().anyMatch(owner -> !owner.equals(resolvedAccount))) {
                throw conflict("A provider identity is linked to another account. Resolve the link conflict first.");
            }
        }
        requireVerifiedSlackCaches(identities, users);
        List<PersonIdentity> ordered = identities.stream()
                .sorted(Comparator.comparingLong(PersonIdentity::providerId)
                        .thenComparing(PersonIdentity::subject)
                        .thenComparing(i -> Objects.requireNonNullElse(i.teamId(), "")))
                .toList();
        PersonScope primary = new PersonScope(
                resolvedAccount, ordered, users.stream().distinct().sorted().toList());
        Set<Long> conversations = new LinkedHashSet<>();
        Set<Long> documents = new LinkedHashSet<>();
        Set<Long> artifacts = new LinkedHashSet<>();
        for (var owner : sourceOwners) {
            var ids = owner.sourceIds(primary);
            if (owner.artifactKinds().contains("chat.conversation_thread")) conversations.addAll(ids);
            if (owner.artifactKinds().contains("docs.document")) documents.addAll(ids);
            if (owner.artifactKinds().contains("scm.issue")) artifacts.addAll(ids);
        }
        return new PersonScope(
                resolvedAccount,
                ordered,
                primary.userIds(),
                conversations.stream().sorted().toList(),
                documents.stream().sorted().toList(),
                artifacts.stream().sorted().toList());
    }

    /** Provider rows may retain different spellings of the same exact network origin. */
    private void includeEquivalentProviders(Set<PersonIdentity> identities) {
        var instances = providerRows();
        for (var identity : List.copyOf(identities)) {
            validateProvider(identity);
            var selected = instances.stream()
                    .filter(provider -> provider.id() == identity.providerId())
                    .findFirst()
                    .orElseThrow(() -> invalid("The provider instance id does not exist"));
            var origin = ScmOrigin.of(selected.serverUrl())
                    .orElseThrow(() ->
                            conflict("The provider instance has no valid origin. Repair its exact instance key."));
            for (var candidate : instances) {
                if (candidate.type().equals(selected.type())
                        && ScmOrigin.of(candidate.serverUrl())
                                .filter(origin::equals)
                                .isPresent()) {
                    identities.add(new PersonIdentity(candidate.id(), identity.subject(), identity.teamId()));
                }
            }
        }
    }

    private record CachedSlackIdentity(
            @Nullable String subject, @Nullable String teamId) {}

    private void requireVerifiedSlackCaches(Set<PersonIdentity> identities, List<Long> users) {
        List<Long> slackProviders = providerRows().stream()
                .filter(provider -> provider.type().equals("SLACK")
                        && ScmOrigin.of(provider.serverUrl())
                                .filter("https://slack.com"::equals)
                                .isPresent())
                .map(Provider::id)
                .toList();
        for (long userId : new LinkedHashSet<>(users)) {
            List<CachedSlackIdentity> caches = jdbc.query(
                    """
                    SELECT author_slack_user_id,slack_team_id FROM slack_message WHERE author_member_id=?
                    UNION SELECT m.slack_user_id,m.slack_team_id FROM mentor_slack_thread m
                        JOIN chat_thread t ON t.id=m.chat_thread_id WHERE t.user_id=?
                    """, (rs, row) -> new CachedSlackIdentity(rs.getString(1), rs.getString(2)), userId, userId);
            for (var cache : caches) {
                if (cache.subject() == null
                        || cache.subject().isBlank()
                        || cache.teamId() == null
                        || cache.teamId().isBlank()
                        || slackProviders.stream()
                                .noneMatch(provider -> identities.contains(
                                        new PersonIdentity(provider, cache.subject(), cache.teamId()))))
                    throw conflict("Collected Slack work has cached SCM attribution without an exact Slack identity. "
                            + "Add its provider instance, native Slack user id, and native workspace id.");
            }
        }
        for (PersonIdentity identity : identities) {
            if (!validateProvider(identity).equals("SLACK")) continue;
            List<Long> cachedActors = jdbc.query(
                    """
                    SELECT author_member_id FROM slack_message
                        WHERE author_slack_user_id=? AND slack_team_id=? AND author_member_id IS NOT NULL
                    UNION SELECT t.user_id FROM mentor_slack_thread m JOIN chat_thread t ON t.id=m.chat_thread_id
                        WHERE m.slack_user_id=? AND m.slack_team_id=?
                    """,
                    (rs, row) -> rs.getLong(1),
                    identity.subject(),
                    identity.teamId(),
                    identity.subject(),
                    identity.teamId());
            if (!users.containsAll(cachedActors))
                throw conflict("Collected Slack work has an unverified cached SCM actor. "
                        + "Add the exact SCM provider identity of the person, or repair the conflicting association.");
        }
    }

    private List<Long> linkedOwners(PersonIdentity identity) {
        String type = validateProvider(identity);
        // Outline links retain the verified OAuth workspace key. An instance/native-user request
        // must check every such link before selecting anything, not silently miss its account.
        boolean outlineInstanceIdentity = type.equals("OUTLINE");
        return jdbc.query(
                "SELECT DISTINCT account_id FROM identity_link WHERE provider_id=? AND subject=? "
                        + "AND (team_id IS NOT DISTINCT FROM ? OR ?)",
                (rs, row) -> rs.getLong(1),
                identity.providerId(),
                identity.subject(),
                identity.teamId(),
                outlineInstanceIdentity);
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
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT server_url='https://slack.com' FROM identity_provider WHERE id=?",
                    Boolean.class,
                    identity.providerId())))
                throw invalid("A Slack identity requires the configured https://slack.com provider instance");
            if (identity.teamId() == null) {
                throw invalid("A Slack identity requires its native Slack workspace id");
            }
        } else if (!type.equals("OUTLINE") && identity.teamId() != null) {
            throw invalid("Only Slack and Outline identities accept a native workspace id");
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
