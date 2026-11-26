package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitHubAppConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Batch;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.CredentialGeneration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository.PendingEmail;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository.PendingRepository;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Corrects commit links from the provider's own answer. Each run skips paused credentials and processes one batch that the
 * provider can read. The queue survives a restart. No transaction is open during a provider call.
 */
@Slf4j
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(
        prefix = "hephaestus.commit-identity-resolution",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("The queue spans shared commit rows; each statement names its own reason")
public class CommitIdentityResolutionJob {

    static final int MAX_ATTEMPTS = 5;

    /** The longest that a provider's deadline holds an email, so a wrong reset cannot park the queue for good. */
    static final Duration MAX_DEFERRAL = Duration.ofDays(1);

    /** One pass of the job: a deadline shorter than that would bring the same email back first at the next pass. */
    static final Duration MIN_DEFERRAL = Duration.ofMinutes(5);

    /** Bound repository batch reads. A stored pause takes no read slot. */
    static final int MAX_READS_PER_RUN = 50;

    private record Turn(int reads, boolean complete) {}

    private final CommitIdentityResolutionRepository resolutions;
    private final ProviderCredentialPauseRepository pauses;
    private final CommitIdentityResolutionService service;
    private final CommitIdentityResolutionProperties properties;
    private final List<CommitIdentityProvider> providers;
    private final RepositoryScopeProvider scopes;
    private final ActiveScmConnections connections;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pending;

    public CommitIdentityResolutionJob(
            CommitIdentityResolutionRepository resolutions,
            ProviderCredentialPauseRepository pauses,
            CommitIdentityResolutionService service,
            CommitIdentityResolutionProperties properties,
            List<CommitIdentityProvider> providers,
            RepositoryScopeProvider scopes,
            ActiveScmConnections connections,
            MeterRegistry meterRegistry) {
        this.resolutions = resolutions;
        this.pauses = pauses;
        this.service = service;
        this.properties = properties;
        this.providers = providers;
        this.scopes = scopes;
        this.connections = connections;
        this.meterRegistry = meterRegistry;
        // Created at 0: increase() ignores the first sample, so a counter born at its first loss would hide that loss.
        meterRegistry.counter(ScmMetrics.COMMIT_IDENTITY_RESOLUTIONS, "outcome", "unanswered");
        meterRegistry.counter(ScmMetrics.COMMIT_IDENTITY_RESOLUTIONS, "outcome", "deferred");
        this.pending =
                meterRegistry.gauge(ScmMetrics.COMMIT_IDENTITY_PENDING, new AtomicLong(resolutions.countPending()));
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @SchedulerLock(name = "commit-identity-resolution", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        Map<CredentialGeneration, Instant> pausedThisRun = new HashMap<>();
        int remaining = MAX_READS_PER_RUN;
        for (var repository : resolutions.findPendingRepositories()) {
            var turn = resolve(repository, remaining, pausedThisRun);
            remaining -= turn.reads();
            if (turn.complete() || remaining == 0) break;
        }
        pending.set(resolutions.countPending());
    }

    private Turn resolve(PendingRepository repository, int budget, Map<CredentialGeneration, Instant> pausedThisRun) {
        var provider = providers.stream()
                .filter(candidate -> candidate.kind().name().equals(repository.getProviderType()))
                .findFirst();
        var triedScopes = new HashSet<Long>();
        List<PendingEmail> unanswered = List.of();
        int reads = 0;
        while (true) {
            var candidates = provider.isEmpty()
                    ? List.<RepositoryAccess>of()
                    : scopes.getActiveScopesForRepository(repository.getNativeId(), repository.getProviderId()).stream()
                            .flatMap(scopeId -> access(scopeId, repository, pausedThisRun).stream())
                            .toList();
            var selected = candidates.stream()
                    .filter(access -> access.pausedUntil() == null && !triedScopes.contains(access.scopeId()))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                var waiting = reads == 0 ? pendingEmails(repository, false) : unanswered;
                return new Turn(reads, deferOrMiss(repository, waiting, candidates, reads > 0));
            }
            if (reads == budget) return new Turn(reads, false);
            if (reads == 0) unanswered = pendingEmails(repository, true);
            if (unanswered.isEmpty()) return new Turn(reads, false);

            var questions = unanswered.stream()
                    .filter(email -> email.getEmail() != null)
                    .map(CommitIdentityResolutionJob::question)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            triedScopes.add(selected.scopeId());
            reads++;
            var batch = provider.orElseThrow()
                    .answer(
                            repository.getNameWithOwner(),
                            repository.getProviderId(),
                            selected.scopeId(),
                            selected.credential(),
                            questions);
            var pause = batch.pause();
            if (pause != null) {
                var generation = pause.credential();
                Instant until = pause.alreadyStored() ? pause.until() : bounded(pause.until());
                if (pause.alreadyStored()
                        || pauses.pause(selected.scopeId(), generation.connectionId(), generation.generation(), until)
                                > 0) {
                    pausedThisRun.put(generation, until);
                }
            }
            unanswered = applyAnswers(repository, unanswered, batch);
            if (pause == null || unanswered.isEmpty()) return new Turn(reads, pause == null);
        }
    }

    private boolean deferOrMiss(
            PendingRepository repository,
            List<PendingEmail> waiting,
            List<RepositoryAccess> candidates,
            boolean attempted) {
        if (candidates.isEmpty() && !attempted) {
            waiting.forEach(this::miss);
            return !waiting.isEmpty();
        }
        if (candidates.stream().allMatch(access -> access.pausedUntil() != null)) {
            candidates.stream()
                    .min(Comparator.comparing(access -> Objects.requireNonNull(access.pausedUntil())))
                    .ifPresent(access -> defer(repository, waiting, access));
        }
        return false;
    }

    private List<PendingEmail> pendingEmails(PendingRepository repository, boolean includeDeferred) {
        return resolutions.findPendingEmails(repository.getRepositoryId(), properties.batchSize(), includeDeferred);
    }

    private List<PendingEmail> applyAnswers(PendingRepository repository, List<PendingEmail> emails, Batch batch) {
        List<PendingEmail> unanswered = new ArrayList<>();
        for (var email : emails) {
            if (email.getEmail() == null) {
                count(service.miss(email.getId(), MAX_ATTEMPTS, MAX_ATTEMPTS));
                continue;
            }
            var answer = batch.answers().get(question(email));
            if (answer == null && batch.pause() != null) {
                unanswered.add(email);
                continue;
            }
            if (answer instanceof Answer.RetryAfterCredentialChange) {
                count(new CommitIdentityResolutionService.Result("deferred", 0));
                continue;
            }
            apply(repository, email, answer);
        }
        return unanswered;
    }

    private Optional<RepositoryAccess> access(
            long scopeId, PendingRepository repository, Map<CredentialGeneration, Instant> pausedThisRun) {
        try {
            return connections
                    .withToken(scopeId)
                    .filter(credential -> credential.bearer() != null || credential.config() instanceof GitHubAppConfig)
                    .filter(credential -> credential.kind().name().equals(repository.getProviderType())
                            && repository
                                    .getServerUrl()
                                    .equals(SourceProviderNamespaces.configuredUrl(credential.config())))
                    .map(credential -> {
                        var generation =
                                new CredentialGeneration(credential.connectionId(), credential.credentialsGeneration());
                        Instant paused = pausedThisRun.get(generation);
                        if (paused == null) {
                            paused = pauses.pausedUntil(scopeId, generation.connectionId(), generation.generation())
                                    .orElse(null);
                        }
                        return new RepositoryAccess(scopeId, credential, generation, paused);
                    });
        } catch (CredentialUnreadableException | MissingCredentialKeyException ignored) {
            // The connection records the unreadable credential. Another workspace may still have access.
            return Optional.empty();
        }
    }

    private record RepositoryAccess(
            long scopeId,
            ActiveScm credential,
            CredentialGeneration generation,
            @Nullable Instant pausedUntil) {}

    private void defer(PendingRepository repository, List<PendingEmail> waiting, RepositoryAccess access) {
        if (waiting.isEmpty()) return;
        var generation = access.generation();
        Instant until = Objects.requireNonNull(access.pausedUntil());
        resolutions.defer(
                waiting.stream().map(PendingEmail::getId).toList(),
                generation.connectionId(),
                generation.generation(),
                until);
        meterRegistry
                .counter(ScmMetrics.COMMIT_IDENTITY_RESOLUTIONS, "outcome", "deferred")
                .increment(waiting.size());
        log.info(
                "Deferred commit link correction until the provider's deadline: repositoryId={}, emails={}, until={}",
                repository.getRepositoryId(),
                waiting.size(),
                until);
    }

    private static Instant bounded(Instant until) {
        Instant now = Instant.now();
        Instant earliest = now.plus(MIN_DEFERRAL);
        Instant latest = now.plus(MAX_DEFERRAL);
        return until.isBefore(earliest) ? earliest : until.isAfter(latest) ? latest : until;
    }

    private void apply(PendingRepository repository, PendingEmail email, @Nullable Answer answer) {
        if (answer == null) {
            miss(email);
            return;
        }
        CommitIdentityResolutionService.Result result;
        try {
            result = service.apply(
                    email.getId(),
                    repository.getRepositoryId(),
                    repository.getProviderId(),
                    Objects.requireNonNull(email.getEmail()),
                    answer);
        } catch (RuntimeException exception) {
            log.warn(
                    "Commit identity correction failed: resolutionId={}, cause={}",
                    email.getId(),
                    exception.getClass().getSimpleName());
            result = service.miss(email.getId(), email.getAttempts(), MAX_ATTEMPTS);
        }
        count(result);
    }

    private void miss(PendingEmail email) {
        count(service.miss(email.getId(), email.getAttempts(), MAX_ATTEMPTS));
    }

    private void count(CommitIdentityResolutionService.Result result) {
        meterRegistry
                .counter(ScmMetrics.COMMIT_IDENTITY_RESOLUTIONS, "outcome", result.outcome())
                .increment();
        meterRegistry.counter(ScmMetrics.COMMIT_IDENTITY_LINK_CHANGES).increment(result.changedLinks());
    }

    private static Question question(PendingEmail email) {
        return new Question(email.getSha(), Objects.requireNonNull(email.getEmail()));
    }
}
