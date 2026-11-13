package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Removal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/** The publication is a wake; persisted intent also repairs a lost wake or process crash. */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Reconciles immutable provider targets; credentials require a surviving scoped owner")
public class ProviderResourceRepairDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ProviderResourceRepairDispatcher.class);
    private final ProviderResourceRepairRepository repairs;
    private final ConnectionProviderRepairRepository references;
    private final ProviderResourceRepairs coordination;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry metrics;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderResourceRepairDispatcher(
            ProviderResourceRepairRepository repairs,
            ConnectionProviderRepairRepository references,
            ProviderResourceRepairs coordination,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry metrics) {
        this.repairs = repairs;
        this.references = references;
        this.coordination = coordination;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.metrics = metrics;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProviderResourceRepairRequested requested) {
        dispatch(requested.repairId());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ConnectionCredentialsReplacedEvent replaced) {
        for (UUID id : coordination.retryWithRestoredAuthorization(replaced.workspaceId(), replaced.connectionId())) {
            dispatch(id);
        }
    }

    public void dispatch(UUID repairId) {
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Provider calls require no active transaction");
        Attempt attempt = transactions.execute(status -> claim(repairId));
        if (attempt == null) return;
        if (attempt.resource().type() == ResourceType.OAUTH_EXCHANGE) {
            transactions.executeWithoutResult(status -> finish(attempt, repairId, Removal.ADMIN_CONFIRMATION));
            return;
        }
        if (attempt.resource().type() == ResourceType.REGISTRATION) {
            try {
                ProviderResourceAdapter adapter =
                        coordination.adapter(attempt.resource().kind()).orElse(null);
                if (adapter != null)
                    coordination.recordDiscovered(
                            repairId, adapter.discoverRegistration(attempt.resource(), attempt.credential()));
            } catch (RuntimeException failure) {
                metrics.counter(
                                IntegrationCoreMetrics.INTEGRATION_PROVIDER_REPAIR_ATTEMPTS,
                                "kind",
                                attempt.resource().kind().name().toLowerCase(Locale.ROOT),
                                "outcome",
                                "discovery_unconfirmed")
                        .increment();
            }
            return;
        }
        Removal result;
        try {
            ProviderResourceAdapter adapter =
                    coordination.adapter(attempt.resource().kind()).orElse(null);
            result = adapter == null
                    ? Removal.NEEDS_AUTHORIZATION
                    : adapter.remove(
                            attempt.resource(),
                            attempt.credential(),
                            () -> Boolean.TRUE.equals(
                                    transactions.execute(status -> permitDispatch(repairId, attempt))));
        } catch (RuntimeException failure) {
            // Provider exceptions can contain a token, signed URL or response body. Keep only a bounded outcome.
            result = Removal.AWAITING_CONFIRMATION;
        }
        Removal outcome = result;
        transactions.executeWithoutResult(status -> finish(attempt, repairId, outcome));
        metrics.counter(
                        IntegrationCoreMetrics.INTEGRATION_PROVIDER_REPAIR_ATTEMPTS,
                        "kind",
                        attempt.resource().kind().name().toLowerCase(Locale.ROOT),
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    private @Nullable Attempt claim(UUID id) {
        ProviderResourceRepair repair = repairs.findById(id).orElse(null);
        if (repair == null) return null;
        coordination.coordinate(List.of(repair.resource()));
        Objects.requireNonNull(entityManager).clear();
        repair = repairs.findById(id).orElse(null);
        if (repair == null
                || repair.getNextAttemptAt().isAfter(clock.instant())
                || repair.getState() == ProviderResourceRepair.State.CONFIRMATION_REQUIRED) return null;
        CredentialBundle credential = removalCredential(repair.resource(), references.findByRepair_Id(id));
        repair.transition(ProviderResourceRepair.State.CHECKING, clock.instant());
        return new Attempt(repair.resource(), credential, repair.getAttemptEpoch());
    }

    private @Nullable CredentialBundle removalCredential(Resource target, List<ConnectionProviderRepair> owners) {
        if (target.kind() == IntegrationKind.GITHUB || target.type() == ResourceType.BOT_USER) return null;
        for (var reference : owners) {
            var owner = reference.getConnection();
            var config = owner.getConfig();
            boolean matches = switch (config) {
                case ConnectionConfig.GitLabConfig gitlab ->
                    ScmOrigin.of(gitlab.serverUrl())
                                    .filter(target.origin()::equals)
                                    .isPresent()
                            && gitlab.gitlabGroupId().toString().equals(target.parentId());
                case ConnectionConfig.OutlineConfig outline ->
                    ScmOrigin.of(outline.serverUrl())
                            .filter(target.origin()::equals)
                            .isPresent();
                case ConnectionConfig.SlackConfig slack ->
                    slack.botTokenFingerprint() == null
                            || slack.botTokenFingerprint().equals(target.resourceId());
                default -> false;
            };
            if (!matches) continue;
            CredentialBundle candidate = coordination.retainedCredential(owner);
            if (target.type() == ResourceType.BOT_TOKEN
                    && (!(candidate instanceof BearerToken bearer)
                            || !ProviderResourceAdapter.slackTokenFingerprint(bearer.token())
                                    .equals(target.resourceId()))) continue;
            if (candidate != null) return candidate;
        }
        return null;
    }

    private boolean permitDispatch(UUID id, Attempt attempt) {
        coordination.coordinate(List.of(attempt.resource()));
        ProviderResourceRepair repair = repairs.findById(id).orElse(null);
        if (repair == null
                || repair.getAttemptEpoch() != attempt.epoch()
                || repair.getState() != ProviderResourceRepair.State.CHECKING) return false;
        repair.transition(ProviderResourceRepair.State.DISPATCHING, clock.instant());
        return true;
    }

    private void finish(Attempt attempt, UUID id, Removal outcome) {
        Resource resource = attempt.resource();
        coordination.coordinate(List.of(resource));
        ProviderResourceRepair repair = repairs.findById(id).orElse(null);
        if (repair == null
                || repair.getAttemptEpoch() != attempt.epoch()
                || (repair.getState() != ProviderResourceRepair.State.CHECKING
                        && repair.getState() != ProviderResourceRepair.State.DISPATCHING)) return;
        boolean verified = outcome == Removal.VERIFIED_ABSENT || outcome == Removal.DIFFERENT_OWNER;
        boolean terminal = outcome == Removal.TERMINAL_SUCCESS;
        if (verified || terminal) coordination.clearCompletedHook(id, resource);
        if ((verified && repair.getRemovalAttempts() == 0) || (terminal && repair.getRemovalAttempts() == 1)) {
            // A token removal must not leave its related bot-user guard silently released.
            for (ConnectionProviderRepair ref : references.findByRepair_Id(id)) references.delete(ref);
            repairs.delete(repair);
            return;
        }
        ProviderResourceRepair.State next = (verified || terminal || outcome == Removal.ADMIN_CONFIRMATION)
                ? ProviderResourceRepair.State.CONFIRMATION_REQUIRED
                : (outcome == Removal.NEEDS_AUTHORIZATION
                        ? ProviderResourceRepair.State.NEEDS_AUTHORIZATION
                        : ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        repair.transition(next, clock.instant());
        log.info(
                "Provider resource repair remains unresolved: repairId={}, kind={}, outcome={}",
                id,
                resource.kind(),
                outcome);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "provider-resource-repair", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void reconcile() {
        for (var candidate : coordination.suspendedOutlineHooks()) {
            coordination.captureSuspendedHook(candidate.workspaceId(), candidate.connectionId());
        }
        for (ProviderResourceRepair repair :
                repairs.findFirst100ByStateNotAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        ProviderResourceRepair.State.CONFIRMATION_REQUIRED, clock.instant())) {
            dispatch(repair.getId());
        }
    }

    private record Attempt(Resource resource, @Nullable CredentialBundle credential, long epoch) {
        @Override
        public String toString() {
            return "Attempt[kind=" + resource.kind() + "]";
        }
    }
}
