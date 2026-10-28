package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextRevocations;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

/**
 * The Heph host is a worker capability: it runs where the Docker sandbox runs, so the monolith runs it too. Artifact
 * generation and the image's training run start no host and claim no turn.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
@Import(HephHostChannelConfiguration.class)
public class HephHostConfiguration {

    static final String PROFILES = "!specs & !cds-training";

    @Bean
    HephHost hephHost(HostSandbox sandbox) {
        return new HephHost(sandbox);
    }

    /**
     * Turn sandboxes that wait empty, ahead of the turns that take them. The chart's {@code sandbox-pod-requests.json} sizes the
     * conversations quota by it.
     */
    public static final int SANDBOX_POOL = 2;

    /** How often {@link TurnSandboxes#reconcile} runs; its first run is at startup. */
    static final Duration SANDBOX_RECONCILE = Duration.ofSeconds(60);

    @Bean
    TurnContextGenerations turnContextGenerations() {
        return new TurnContextGenerations();
    }

    /**
     * The worker's turn sandboxes. A change of what a workspace's turns may read, on any process, makes a new context
     * generation and removes that workspace's sandboxes here ({@link TurnContextRevocations}).
     */
    @Bean(destroyMethod = "close")
    TurnSandboxes turnSandboxes(
            ExecSandboxes sandboxes, MentorLedgerNotifications notifications, TurnContextGenerations generations) {
        TurnSandboxes turnSandboxes = new TurnSandboxes(sandboxes, SANDBOX_POOL);
        notifications.subscribe(new TurnContextRevocations(generations, turnSandboxes));
        return turnSandboxes;
    }

    @Bean
    TurnContextFolder turnContextFolder(TurnSandboxes sandboxes, ObjectProvider<ContextLoader> loaders, Clock clock) {
        return new TurnContextFolder(sandboxes, loaders, clock);
    }

    @Bean
    HephTurnDispatcher hephTurnDispatcher(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnCompletion completion,
            MentorLedgerNotifications notifications,
            HephHost host,
            ProxyCallsInFlight callsInFlight,
            TurnSandboxes sandboxes,
            WorkerJwtIssuer issuer,
            ObjectMapper mapper,
            Clock clock,
            MeterRegistry meters,
            WorkerProperties worker,
            @Value("${hephaestus.mentor.turn.lease:PT1M}") Duration lease) {
        return new HephTurnDispatcher(
                starter,
                turns,
                completion,
                notifications,
                host,
                callsInFlight,
                sandboxes,
                issuer,
                mapper,
                clock,
                meters,
                worker.resolvedWorkerId(),
                worker.capacity().resolveMentorMax(),
                lease,
                HephTurnDispatcher.STOP_GRACE,
                worker.drain().timeout());
    }

    /**
     * The dispatcher's tick, alone on the thread of this registrar: workers run without {@code @EnableScheduling}. The
     * tick renews the leases of running turns, so no sandbox call may wait on its thread: a Kubernetes API that stalls
     * for a minute would expire the lease of a turn that runs well. The tick stops when this bean is destroyed, after
     * the dispatcher's drain, so leases are renewed while turns drain.
     */
    @Bean
    ScheduledTaskRegistrar hephTurnTicks(HephTurnDispatcher dispatcher) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(dispatcher::tick, HephTurnDispatcher.TICK, HephTurnDispatcher.TICK));
        return tasks;
    }

    /** {@link TurnSandboxes#reconcile} on a thread of its own: it lists and removes sandboxes, which can wait. */
    @Bean
    ScheduledTaskRegistrar turnSandboxReconciles(HephTurnDispatcher dispatcher, TurnSandboxes sandboxes) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(
                new FixedDelayTask(() -> sandboxes.reconcile(dispatcher::runs), SANDBOX_RECONCILE, Duration.ZERO));
        return tasks;
    }
}
