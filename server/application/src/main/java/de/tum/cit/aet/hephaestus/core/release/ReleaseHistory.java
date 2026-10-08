package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.RunningReleaseDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The releases this instance ran, in the order its server role started them. The server role runs
 * the schema migrations, so it is the one role that starts once per deployment; a restart of the same
 * release adds nothing, and a rollback adds the older release again.
 */
@Service
@ConditionalOnServerRole
public class ReleaseHistory {
    private static final Logger log = LoggerFactory.getLogger(ReleaseHistory.class);

    private final RunningRelease running;
    private final ReleaseStartRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Environment environment;

    ReleaseHistory(
            RunningRelease running,
            ReleaseStartRepository repository,
            TransactionTemplate transaction,
            Clock clock,
            Environment environment) {
        this.running = running;
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.environment = environment;
    }

    static ReleaseStart startOf(RunningReleaseDTO release, Instant at) {
        return new ReleaseStart(
                release.version(), release.channel(), release.environment(), release.commit(), release.image(), at);
    }

    static boolean describes(ReleaseStart start, RunningReleaseDTO release) {
        return start.getVersion().equals(release.version())
                && start.getEnvironment().equals(release.environment())
                && Objects.equals(start.getCommit(), release.commit())
                && Objects.equals(start.getImage(), release.image());
    }

    @EventListener(ApplicationReadyEvent.class)
    @WorkspaceAgnostic("Release starts are instance-wide; they name no tenant")
    public void recordStart() {
        // Generating the API spec boots without the schema.
        if (environment.matchesProfiles("specs")) return;
        var identity = running.get();
        try {
            transaction.executeWithoutResult(status -> {
                repository.lockHistory();
                boolean unchanged = repository
                        .findFirstByOrderByIdDesc()
                        .map(latest -> describes(latest, identity))
                        .orElse(false);
                if (!unchanged) repository.save(startOf(identity, clock.instant()));
            });
        } catch (DataAccessException | TransactionException e) {
            // The history informs administrators; it must not keep the release from serving.
            log.atWarn()
                    .addKeyValue("event.name", "release.history.failed")
                    .setCause(e)
                    .log("Could not record the start of release {}", identity.version());
        }
    }

    /** The ten most recent starts, newest first. */
    List<ReleaseStart> recent() {
        return repository.findTop10ByOrderByIdDesc();
    }
}
