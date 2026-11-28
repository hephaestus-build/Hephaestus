package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rollouts of the server in this deployment environment (docs/admin/instance-admin.mdx § Update checks).
 *
 * <p>The Deployment controller orders rollouts: it gives each one the next revision, also a rollback that scales an
 * earlier ReplicaSet up again. Every ready server reads the revision of its own ReplicaSet each minute and records it
 * once. A late restart of an old server reads an older revision, which is recorded already or sorts below the newer
 * one, so it never becomes the newest entry. A rollback reads a new revision. A server outside a Deployment has no
 * revision and records its start once.
 */
@Service
@ConditionalOnServerRole
public class ReleaseHistory {
    private static final Logger log = LoggerFactory.getLogger(ReleaseHistory.class);

    /** The history shows ten entries; the rest give a reader of the table some context. */
    static final int KEPT_ACTIVATIONS = 100;

    /** What this process recorded: the rollout it runs, or the row of its start. */
    private sealed interface Recorded {
        boolean is(ReleaseActivation entry);
    }

    private record RecordedRollout(Rollout rollout) implements Recorded {
        @Override
        public boolean is(ReleaseActivation entry) {
            return rollout.deploymentUid().equals(entry.getDeploymentUid())
                    && Objects.equals(rollout.revision(), entry.getRevision());
        }
    }

    private record RecordedStart(long id) implements Recorded {
        @Override
        public boolean is(ReleaseActivation entry) {
            return Objects.equals(entry.getId(), id);
        }
    }

    private final RunningRelease running;
    private final ReleaseActivationRepository repository;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final @Nullable RolloutReader rollouts;
    private final AtomicReference<@Nullable Recorded> recorded = new AtomicReference<>();
    private final AtomicBoolean ready = new AtomicBoolean();

    ReleaseHistory(
            RunningRelease running,
            ReleaseActivationRepository repository,
            TransactionTemplate transaction,
            Environment environment,
            ObjectProvider<RolloutReader> rollouts) {
        this.running = running;
        this.repository = repository;
        this.transaction = transaction;
        this.environment = environment;
        this.rollouts = rollouts.getIfAvailable();
    }

    /** Starts the reports: a server that never became ready records nothing. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ready.set(true);
        report();
    }

    /**
     * Records the rollout that runs this server, or this server's start. A rollout reads again every minute, because
     * {@code kubectl rollout undo} of a rollout that never scaled its ReplicaSet down starts no new server. The timer
     * starts before the server is ready, so it waits for {@link #onApplicationReady()}, and one report runs at a time:
     * two could each record the same server start.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    @WorkspaceAgnostic("Release history is instance-wide; it names no tenant")
    public synchronized void report() {
        // Generating the API spec boots without the schema.
        if (!ready.get() || environment.matchesProfiles("specs")) return;
        if (rollouts == null && recorded.get() != null) return;
        var identity = running.get();
        try {
            if (rollouts != null) {
                Rollout rollout = rollouts.read();
                transaction.executeWithoutResult(status -> {
                    repository.recordRollout(identity, rollout);
                    repository.keepNewest(identity.environment(), KEPT_ACTIVATIONS);
                });
                recorded.set(new RecordedRollout(rollout));
            } else {
                Long id = transaction.execute(status -> {
                    long start = repository.recordStart(identity);
                    repository.keepNewest(identity.environment(), KEPT_ACTIVATIONS);
                    return start;
                });
                recorded.set(new RecordedStart(Objects.requireNonNull(id)));
            }
        } catch (RuntimeException e) {
            // A rollout that this server can no longer read may have changed under it: "running since" is unknown
            // until a read succeeds, rather than the date of the last read.
            if (rollouts != null) recorded.set(null);
            // The history informs administrators; it must not keep the release from serving. The database and the
            // Kubernetes client both fail with unchecked exceptions, and the next report retries.
            log.atWarn()
                    .addKeyValue("event.name", "release.history.failed")
                    .setCause(e)
                    .log("Could not record release {}", identity.version());
        }
    }

    /** The ten newest entries of this deployment environment, newest first. */
    List<ReleaseActivation> recent() {
        return repository.findRecent(running.get().environment());
    }

    /**
     * When the newest entry was recorded, if this server runs it. An old server that serves a request during a rollout
     * does not claim the newest entry.
     */
    @Nullable
    Instant runningSince(List<ReleaseActivation> recent) {
        Recorded mine = recorded.get();
        if (mine == null || recent.isEmpty()) return null;
        ReleaseActivation newest = recent.getFirst();
        return mine.is(newest) ? newest.getActivatedAt() : null;
    }
}
