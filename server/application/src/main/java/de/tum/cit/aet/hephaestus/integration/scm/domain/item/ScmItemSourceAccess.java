package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.scm.ScmPersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads current access and consent only; historical content remains in retained custody. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmItemSourceAccess implements ItemSourceAccess {
    private final ScmItemAccess items;
    private final ItemIndexService index;
    private final RepositoryRepository repositories;
    private final PullRequestReviewRepository reviews;
    private final ScmRepositoryAccess access;
    private final ScmPersonSourceIdentityContributor contributors;
    private final PersonProcessingSuppression suppression;
    private final PersonIdentityResolver identities;
    private final AccountDirectory accounts;

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(
                ScmItemEvents.REPOSITORY,
                ScmItemEvents.FILE,
                ScmItemEvents.ISSUE,
                ScmItemEvents.PULL_REQUEST,
                ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose) {
        Objects.requireNonNull(purpose);
        return permitsItem(item, SourceReadAudience.PROCESSING, new Direct());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience) {
        Objects.requireNonNull(purpose);
        return permitsItem(item, Objects.requireNonNull(audience), new Direct());
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Map<ItemRef, BooleanSupplier> prepareChecks(
            Collection<ItemRef> refs, SourceUsePurpose purpose, SourceReadAudience audience) {
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(audience);
        if (refs.isEmpty()) return Map.of();
        long workspaceId = refs.iterator().next().workspaceId();
        var facts = new Read(
                workspaceId,
                refs.stream().filter(ref -> ref.workspaceId() == workspaceId).toList());
        Map<ItemRef, BooleanSupplier> checks = new HashMap<>();
        for (var ref : refs) {
            if (ref.workspaceId() != workspaceId) continue;
            checks.put(ref, () -> permitsItem(ref, audience, facts));
        }
        return checks;
    }

    // The audience reaches every parent, and changes only whether a contributor's AI choice applies.
    private boolean permitsItem(ItemRef item, SourceReadAudience audience, Facts facts) {
        if (!kinds().contains(item.kind())) return false;
        try {
            facts.require(item);
            Long source = item.sourceId();
            if (source != null && facts.sourceSuppressed(item.workspaceId(), item.kind(), source)) return false;
            if (item.kind().equals(ScmItemEvents.REPOSITORY)) {
                if (source == null || item.parentId() != null || item.path() != null) return false;
                var repository = facts.repository(source);
                if (repository.isEmpty()) return false;
                facts.authorize(item.workspaceId(), repository.orElseThrow());
                return true;
            }
            if (item.kind().equals(ScmItemEvents.FILE)) {
                if (source != null || item.path() == null) return false;
                return permitsItem(facts.parent(item, ScmItemEvents.REPOSITORY), audience, facts);
            }
            if (source == null || item.path() != null) return false;
            if (item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
                var parent = facts.parent(item, ScmItemEvents.PULL_REQUEST);
                if (!permitsItem(parent, audience, facts)) return false;
                var review = reviews.findById(source);
                if (!review.map(row -> row.getPullRequest() != null
                                && Objects.equals(row.getPullRequest().getId(), parent.sourceId()))
                        .orElse(false)) return false;
            } else {
                var parent = facts.parent(item, ScmItemEvents.REPOSITORY);
                if (!permitsItem(parent, audience, facts)) return false;
                // A transfer can commit between checks of two items in the same read.
                var work = items.work(item.kind(), source);
                if (!work.map(row -> !row.getDeleted()
                                && row.getRepositoryId() != null
                                && Objects.equals(row.getRepositoryId(), parent.sourceId()))
                        .orElse(false)) return false;
            }
            for (var identity : facts.contributors(item.workspaceId(), item.kind(), source)) {
                if (facts.personSuppressed(identity)) return false;
                var accountId = facts.accountFor(identity);
                if (accountId.isPresent()) {
                    var account = facts.account(accountId.orElseThrow());
                    if (account.isEmpty()) return false;
                    var person = account.orElseThrow();
                    if (facts.accountSuppressed(person.id())
                            || (audience == SourceReadAudience.PROCESSING && person.aiChoice() == AiChoice.NO_AI)
                            || (person.status() != Account.Status.ACTIVE
                                    && person.status() != Account.Status.UNCLAIMED)) return false;
                }
            }
            return true;
        } catch (ItemCaptureException unavailable) {
            return false;
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() == HttpStatus.CONFLICT.value()) return false;
            throw conflict;
        }
    }

    /** What the checks of {@link #permitsItem} read, one lookup per question. */
    private abstract static class Facts {
        abstract void require(ItemRef item);

        abstract ItemRef parent(ItemRef item, ItemKind kind);

        abstract boolean sourceSuppressed(long workspaceId, ItemKind kind, long source);

        abstract Optional<Repository> repository(long id);

        abstract void authorize(long workspaceId, Repository repository);

        abstract List<PersonIdentity> contributors(long workspaceId, ItemKind kind, long source);

        abstract boolean personSuppressed(PersonIdentity identity);

        abstract Optional<Long> accountFor(PersonIdentity identity);

        abstract Optional<AccountDirectory.AccountView> account(long id);

        abstract boolean accountSuppressed(long id);
    }

    /** Each lookup as its own query: the check of one item. */
    private final class Direct extends Facts {
        @Override
        public void require(ItemRef item) {
            items.require(item);
        }

        @Override
        public ItemRef parent(ItemRef item, ItemKind kind) {
            return items.parent(item, kind);
        }

        @Override
        public boolean sourceSuppressed(long workspaceId, ItemKind kind, long source) {
            return suppression.isSourceSuppressed(workspaceId, kind.value(), source);
        }

        @Override
        public Optional<Repository> repository(long id) {
            return repositories.findById(id);
        }

        @Override
        public void authorize(long workspaceId, Repository repository) {
            access.authorize(workspaceId, repository);
        }

        @Override
        public List<PersonIdentity> contributors(long workspaceId, ItemKind kind, long source) {
            return contributors.identitiesForSource(workspaceId, kind.value(), source);
        }

        @Override
        public boolean personSuppressed(PersonIdentity identity) {
            return suppression.isSuppressed(identity.providerId(), identity.subject(), identity.teamId());
        }

        @Override
        public Optional<Long> accountFor(PersonIdentity identity) {
            return identities.accountFor(identity);
        }

        @Override
        public Optional<AccountDirectory.AccountView> account(long id) {
            return accounts.find(id);
        }

        @Override
        public boolean accountSuppressed(long id) {
            return suppression.isAccountSuppressed(id);
        }
    }

    /**
     * The same lookups for many items of one workspace. Items, their parents, source suppression and contributors
     * are each read for all items of a kind at once; a repository, a person and an account are each read
     * once, however many items share them. A lookup that fails fails again for each item that asks, as it would alone.
     */
    private final class Read extends Facts {
        private final long workspaceId;
        private final Map<Long, ItemIndexService.IndexedItem> stored = new HashMap<>();
        private final Map<ItemKind, Set<Long>> sources = new HashMap<>();
        private final BySource<Boolean> suppressedSources;
        private final BySource<List<PersonIdentity>> people;
        private final Map<Long, Optional<Repository>> repositoryRows = new HashMap<>();
        private final Map<Long, Outcome<Boolean>> authorized = new HashMap<>();
        private final Map<PersonIdentity, Boolean> suppressedPeople = new HashMap<>();
        private final Map<PersonIdentity, Outcome<Optional<Long>>> owners = new HashMap<>();
        private final Map<Long, Optional<AccountDirectory.AccountView>> accountRows = new HashMap<>();
        private final Map<Long, Boolean> suppressedAccounts = new HashMap<>();

        Read(long workspaceId, List<ItemRef> refs) {
            this.workspaceId = workspaceId;
            suppressedSources = new BySource<>((kind, ids) -> {
                var suppressed = suppression.suppressedSources(workspaceId, kind.value(), ids);
                Map<Long, Boolean> answers = new HashMap<>();
                ids.forEach(id -> answers.put(id, suppressed.contains(id)));
                return answers;
            });
            people = new BySource<>((kind, ids) -> contributors.identitiesForSources(workspaceId, kind.value(), ids));
            // An item, its parent and a review's pull request's repository: at most three levels.
            Set<Long> wanted = refs.stream().map(ItemRef::itemId).collect(Collectors.toSet());
            for (int level = 0; level < 3 && !wanted.isEmpty(); level++) {
                stored.putAll(index.findAllIndexed(workspaceId, wanted));
                wanted = wanted.stream()
                        .map(stored::get)
                        .filter(Objects::nonNull)
                        .map(row -> row.ref().parentId())
                        .filter(Objects::nonNull)
                        .filter(id -> !stored.containsKey(id))
                        .collect(Collectors.toSet());
            }
            for (var row : stored.values()) {
                var ref = row.ref();
                if (ref.sourceId() != null)
                    sources.computeIfAbsent(ref.kind(), ignored -> new HashSet<>())
                            .add(ref.sourceId());
            }
            var repositoryIds = sources.getOrDefault(ScmItemEvents.REPOSITORY, Set.of());
            if (!repositoryIds.isEmpty()) {
                var found = repositories.findAllById(repositoryIds).stream()
                        .collect(Collectors.toMap(Repository::getId, Function.identity()));
                repositoryIds.forEach(id -> repositoryRows.put(id, Optional.ofNullable(found.get(id))));
            }
        }

        private Optional<ItemIndexService.IndexedItem> stored(long itemId) {
            return Optional.ofNullable(stored.get(itemId));
        }

        @Override
        public void require(ItemRef item) {
            checkWorkspace(item.workspaceId());
            items.require(item, this::stored);
        }

        @Override
        public ItemRef parent(ItemRef item, ItemKind kind) {
            checkWorkspace(item.workspaceId());
            return items.parent(item, kind, this::stored);
        }

        @Override
        public boolean sourceSuppressed(long workspaceId, ItemKind kind, long source) {
            checkWorkspace(workspaceId);
            return suppressedSources.get(kind, source);
        }

        @Override
        public Optional<Repository> repository(long id) {
            return repositoryRows.computeIfAbsent(id, repositories::findById);
        }

        @Override
        public void authorize(long workspaceId, Repository repository) {
            checkWorkspace(workspaceId);
            authorized
                    .computeIfAbsent(
                            repository.getId(),
                            ignored -> Outcome.of(() -> {
                                access.authorize(workspaceId, repository);
                                return true;
                            }))
                    .get();
        }

        @Override
        public List<PersonIdentity> contributors(long workspaceId, ItemKind kind, long source) {
            checkWorkspace(workspaceId);
            return people.get(kind, source);
        }

        @Override
        public boolean personSuppressed(PersonIdentity identity) {
            return suppressedPeople.computeIfAbsent(
                    identity, key -> suppression.isSuppressed(key.providerId(), key.subject(), key.teamId()));
        }

        @Override
        public Optional<Long> accountFor(PersonIdentity identity) {
            return owners.computeIfAbsent(identity, key -> Outcome.of(() -> identities.accountFor(key)))
                    .get();
        }

        @Override
        public Optional<AccountDirectory.AccountView> account(long id) {
            return accountRows.computeIfAbsent(id, accounts::find);
        }

        @Override
        public boolean accountSuppressed(long id) {
            return suppressedAccounts.computeIfAbsent(id, suppression::isAccountSuppressed);
        }

        private void checkWorkspace(long other) {
            if (other != workspaceId) throw new IllegalStateException("One read covers one workspace");
        }

        /**
         * One answer per source of a kind. The first question about a kind reads every stored source of it; a source
         * asked later without an answer is read with the other unanswered ones then.
         */
        private final class BySource<T> {
            private final BiFunction<ItemKind, Set<Long>, Map<Long, T>> read;
            private final Map<ItemKind, Map<Long, T>> answers = new HashMap<>();

            BySource(BiFunction<ItemKind, Set<Long>, Map<Long, T>> read) {
                this.read = read;
            }

            T get(ItemKind kind, long source) {
                var known = answers.computeIfAbsent(kind, ignored -> new HashMap<>());
                var answer = known.get(source);
                if (answer != null) return answer;
                Set<Long> unanswered = new HashSet<>(sources.getOrDefault(kind, Set.of()));
                unanswered.add(source);
                unanswered.removeAll(known.keySet());
                known.putAll(read.apply(kind, unanswered));
                return Objects.requireNonNull(known.get(source));
            }
        }
    }

    /** A lookup's value, or the exception it raised, so that each item that asks sees the same. */
    private record Outcome<T>(@Nullable T value, @Nullable RuntimeException failure) {
        static <T> Outcome<T> of(Supplier<T> lookup) {
            try {
                return new Outcome<>(lookup.get(), null);
            } catch (RuntimeException failed) {
                return new Outcome<>(null, failed);
            }
        }

        T get() {
            if (failure != null) throw failure;
            return Objects.requireNonNull(value);
        }
    }
}
