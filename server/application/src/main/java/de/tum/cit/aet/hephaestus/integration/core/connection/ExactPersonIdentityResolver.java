package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderPersonLookup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
    private final List<ProviderPersonLookup> mirrors;

    // Source attribution can consult this resolver for current account controls.
    private final ObjectProvider<PersonSourceIdentityContributor> sourceOwners;

    public ExactPersonIdentityResolver(
            JdbcTemplate jdbc,
            ObjectProvider<PersonSourceIdentityContributor> sourceOwners,
            List<ProviderPersonLookup> mirrors) {
        this.jdbc = jdbc;
        this.mirrors = List.copyOf(mirrors);
        this.sourceOwners = sourceOwners;
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
    public Optional<Long> accountFor(PersonIdentity identity) {
        Set<PersonIdentity> identities = new LinkedHashSet<>(List.of(identity));
        includeEquivalentProviders(identities);
        Set<Long> owners = new LinkedHashSet<>();
        for (var exact : identities) owners.addAll(linkedOwners(exact));
        if (owners.size() > 1) throw conflict("The exact provider identity is linked to different accounts.");
        return owners.stream().findFirst();
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
                    "SELECT provider_id, provider_user_key, NULLIF(team_key,'') AS team_id FROM identity WHERE account_id = ?",
                    (rs, row) -> new PersonIdentity(
                            rs.getLong("provider_id"),
                            Objects.requireNonNull(rs.getString("provider_user_key")),
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
            for (var mirror : mirrors)
                if (mirror.providerTypes().contains(type)) users.addAll(mirror.mirrorIds(identity));
            // Check every link again, including closure additions and disabled links. An account
            // with an internally conflicting link must not widen an erasure request silently.
            List<Long> linked = linkedOwners(identity);
            if (linked.stream().anyMatch(owner -> !owner.equals(resolvedAccount))) {
                throw conflict("A provider identity is linked to another account. Resolve the link conflict first.");
            }
        }
        List<PersonIdentity> ordered = identities.stream()
                .sorted(Comparator.comparingLong(PersonIdentity::providerId)
                        .thenComparing(PersonIdentity::subject)
                        .thenComparing(i -> Objects.requireNonNullElse(i.teamId(), "")))
                .toList();
        PersonScope primary = new PersonScope(
                resolvedAccount, ordered, users.stream().distinct().sorted().toList());
        Set<Long> conversations = new LinkedHashSet<>();
        Set<Long> documents = new LinkedHashSet<>();
        Set<Long> scmSources = new LinkedHashSet<>();
        for (var owner : sourceOwners.orderedStream().toList()) {
            var ids = owner.sourceIds(primary);
            switch (owner.scope()) {
                case CONVERSATIONS -> conversations.addAll(ids);
                case DOCUMENTS -> documents.addAll(ids);
                case SCM_SOURCES -> scmSources.addAll(ids);
            }
        }
        return new PersonScope(
                resolvedAccount,
                ordered,
                primary.userIds(),
                conversations.stream().sorted().toList(),
                documents.stream().sorted().toList(),
                scmSources.stream().sorted().toList());
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

    private List<Long> linkedOwners(PersonIdentity identity) {
        String type = validateProvider(identity);
        // An instance-only Outline request checks all verified teams; a supplied team stays exact.
        boolean outlineInstanceIdentity = type.equals("OUTLINE") && identity.teamId() == null;
        return jdbc.query(
                "SELECT DISTINCT account_id FROM identity WHERE provider_id=? AND provider_user_key=? AND (team_key=COALESCE(?,'') OR ?)",
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
        return type;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
