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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        this.pending =
                meterRegistry.gauge(ScmMetrics.COMMIT_IDENTITY_PENDING, new AtomicLong(resolutions.countPending()));
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @SchedulerLock(name = "commit-identity-resolution", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        resolutions.findNextRepository().ifPresent(this::resolve);
        pending.set(resolutions.countPending());
    }

    private void resolve(NextRepository repository) {
        var emails = resolutions.findPendingEmails(repository.getRepositoryId(), properties.batchSize());
        var provider = providers.stream()
                .filter(candidate -> candidate.kind().name().equals(repository.getProviderType()))
                .findFirst();
        var scope = provider.flatMap(candidate ->
                scopes.getActiveScopesForRepository(repository.getNativeId(), repository.getProviderId()).stream()
                        .findFirst());
        if (scope.isEmpty()) {
            emails.forEach(this::miss);
            return;
        }
        var questions = emails.stream()
                .filter(email -> email.getEmail() != null)
                .map(CommitIdentityResolutionJob::question)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var answers = provider.orElseThrow()
                .answer(repository.getNameWithOwner(), repository.getProviderId(), scope.orElseThrow(), questions);
        for (var email : emails) {
            if (email.getEmail() == null) {
                count(service.miss(email.getId(), MAX_ATTEMPTS, MAX_ATTEMPTS));
                continue;
            }
            apply(repository, email, answers);
        }
    }

    private void apply(NextRepository repository, PendingEmail email, Map<Question, Answer> answers) {
        var answer = answers.get(question(email));
        if (answer instanceof Answer.Deferred) {
            return;
        }
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
