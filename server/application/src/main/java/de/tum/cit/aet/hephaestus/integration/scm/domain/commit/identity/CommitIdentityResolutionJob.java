package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository.NextRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository.PendingEmail;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Corrects commit links from the provider's own answer, one small batch per run. Progress lives in the queue table, so a
 * restart resumes. No transaction is open during a provider call, and a missing answer changes nothing.
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
    static final Duration MAX_DEFERRAL = Duration.ofHours(2);

    static final Duration MIN_DEFERRAL = Duration.ofMinutes(1);

    /** How many repositories of paused scopes one run sets aside, at most, before it asks a provider. */
    static final int MAX_SET_ASIDE_PER_RUN = 50;

    /**
     * The deadline per scope that a provider gave on this server. The deferred rows carry it for every server; this
     * map stops the reads of the scope's other repositories here, so a throttle costs one request per server.
     */
    private final Map<Long, Instant> pausedScopes = new ConcurrentHashMap<>();

    private final CommitIdentityResolutionRepository resolutions;
    private final CommitIdentityResolutionService service;
    private final CommitIdentityResolutionProperties properties;
    private final List<CommitIdentityProvider> providers;
    private final RepositoryScopeProvider scopes;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pending;

    public CommitIdentityResolutionJob(
            CommitIdentityResolutionRepository resolutions,
            CommitIdentityResolutionService service,
            CommitIdentityResolutionProperties properties,
            List<CommitIdentityProvider> providers,
            RepositoryScopeProvider scopes,
            MeterRegistry meterRegistry) {
        this.resolutions = resolutions;
        this.service = service;
        this.properties = properties;
        this.providers = providers;
        this.scopes = scopes;
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
        // A repository of a paused scope is set aside without a request, and the run takes the next due one.
        for (int setAside = 0; setAside <= MAX_SET_ASIDE_PER_RUN; setAside++) {
            var next = resolutions.findNextRepository();
            if (next.isEmpty() || resolve(next.get())) break;
        }
        pending.set(resolutions.countPending());
    }

    /** @return true when it asked the provider; false when it set the repository aside for its paused scope */
    private boolean resolve(NextRepository repository) {
        var emails = resolutions.findPendingEmails(repository.getRepositoryId(), properties.batchSize());
        var provider = providers.stream()
                .filter(candidate -> candidate.kind().name().equals(repository.getProviderType()))
                .findFirst();
        var scope = provider.flatMap(candidate ->
                scopes.getActiveScopesForRepository(repository.getNativeId(), repository.getProviderId()).stream()
                        .findFirst());
        if (scope.isEmpty()) {
            emails.forEach(this::miss);
            return true;
        }
        long scopeId = scope.orElseThrow();
        Instant paused = pausedScopes.get(scopeId);
        if (paused != null && paused.isAfter(Instant.now())) {
            defer(repository, emails.stream().map(PendingEmail::getId).toList(), paused);
            return false;
        }
        pausedScopes.remove(scopeId);
        var questions = emails.stream()
                .filter(email -> email.getEmail() != null)
                .map(CommitIdentityResolutionJob::question)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var answers = provider.orElseThrow()
                .answer(repository.getNameWithOwner(), repository.getProviderId(), scopeId, questions);
        List<Long> deferred = new ArrayList<>();
        Instant until = null;
        for (var email : emails) {
            if (email.getEmail() == null) {
                count(service.miss(email.getId(), MAX_ATTEMPTS, MAX_ATTEMPTS));
                continue;
            }
            if (answers.get(question(email)) instanceof Answer.Deferred deferral) {
                deferred.add(email.getId());
                until = until == null || deferral.until().isAfter(until) ? deferral.until() : until;
                continue;
            }
            apply(repository, email, answers);
        }
        if (until != null) {
            Instant bounded = bounded(until);
            pausedScopes.put(scopeId, bounded);
            defer(repository, deferred, bounded);
        }
        return true;
    }

    private void defer(NextRepository repository, List<Long> ids, Instant until) {
        if (ids.isEmpty()) return;
        resolutions.defer(ids, until);
        meterRegistry
                .counter(ScmMetrics.COMMIT_IDENTITY_RESOLUTIONS, "outcome", "deferred")
                .increment(ids.size());
        log.info(
                "Deferred commit link correction until the provider's deadline: repositoryId={}, emails={}, until={}",
                repository.getRepositoryId(),
                ids.size(),
                until);
    }

    private static Instant bounded(Instant until) {
        Instant now = Instant.now();
        Instant earliest = now.plus(MIN_DEFERRAL);
        Instant latest = now.plus(MAX_DEFERRAL);
        return until.isBefore(earliest) ? earliest : until.isAfter(latest) ? latest : until;
    }

    private void apply(NextRepository repository, PendingEmail email, Map<Question, Answer> answers) {
        var answer = answers.get(question(email));
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
