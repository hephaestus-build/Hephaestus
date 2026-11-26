package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Corrects commit links from the provider's own answer. Each run skips deferred work and processes one batch that the
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
    static final Duration MAX_DEFERRAL = Duration.ofHours(2);

    /** One pass of the job: a deadline shorter than that would bring the same email back first at the next pass. */
    static final Duration MIN_DEFERRAL = Duration.ofMinutes(5);

    /** How many repositories one run takes, at most: each deferred one costs at most one request of its credential. */
    static final int MAX_REPOSITORIES_PER_RUN = 50;

    private enum Turn {
        ANSWERED,
        DEFERRED
    }

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
        // A deferred repository leaves the queue until its deadline, so the run takes the next due one: a throttled
        // credential never holds up a healthy one. The run ends with the first repository that it answered.
        for (int taken = 0; taken < MAX_REPOSITORIES_PER_RUN; taken++) {
            var next = resolutions.findNextRepository();
            if (next.isEmpty() || resolve(next.get()) == Turn.ANSWERED) break;
        }
        pending.set(resolutions.countPending());
    }

    private Turn resolve(NextRepository repository) {
        var emails = resolutions.findPendingEmails(repository.getRepositoryId(), properties.batchSize());
        var provider = providers.stream()
                .filter(candidate -> candidate.kind().name().equals(repository.getProviderType()))
                .findFirst();
        var access = provider.flatMap(candidate ->
                scopes.getActiveScopesForRepository(repository.getNativeId(), repository.getProviderId()).stream()
                        .flatMap(scopeId -> access(scopeId, repository).stream())
                        .findFirst());
        if (access.isEmpty()) {
            emails.forEach(this::miss);
            return Turn.ANSWERED;
        }
        var selected = access.orElseThrow();
        long scopeId = selected.scopeId();
        var credential = selected.credential();
        var paused = pauses.pausedUntil(scopeId, credential.connectionId(), credential.credentialsGeneration());
        if (paused.isPresent()) {
            defer(repository, emails.stream().map(PendingEmail::getId).toList(), credential, paused.get());
            return Turn.DEFERRED;
        }
        var questions = emails.stream()
                .filter(email -> email.getEmail() != null)
                .map(CommitIdentityResolutionJob::question)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var answers = provider.orElseThrow()
                .answer(repository.getNameWithOwner(), repository.getProviderId(), scopeId, credential, questions);
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
        if (until == null) return Turn.ANSWERED;
        Instant bounded = bounded(until);
        pauses.pause(scopeId, credential.connectionId(), credential.credentialsGeneration(), bounded);
        defer(repository, deferred, credential, bounded);
        return Turn.DEFERRED;
    }

    private Optional<RepositoryAccess> access(long scopeId, NextRepository repository) {
        try {
            return connections
                    .withToken(scopeId)
                    .filter(credential -> credential.kind().name().equals(repository.getProviderType())
                            && repository
                                    .getServerUrl()
                                    .equals(SourceProviderNamespaces.configuredUrl(credential.config())))
                    .map(credential -> new RepositoryAccess(scopeId, credential));
        } catch (CredentialUnreadableException | MissingCredentialKeyException ignored) {
            // The connection records the unreadable credential. Another workspace may still have access.
            return Optional.empty();
        }
    }

    private record RepositoryAccess(long scopeId, ActiveScm credential) {}

    private void defer(NextRepository repository, List<Long> ids, ActiveScm credential, Instant until) {
        if (ids.isEmpty()) return;
        resolutions.defer(ids, credential.connectionId(), credential.credentialsGeneration(), until);
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
