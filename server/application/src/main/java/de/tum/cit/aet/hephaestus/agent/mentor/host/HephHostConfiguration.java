package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
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

    @Bean
    HephTurnDispatcher hephTurnDispatcher(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnCompletion completion,
            MentorLedgerNotifications notifications,
            HephHost host,
            ProxyCallsInFlight callsInFlight,
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
                issuer,
                mapper,
                clock,
                meters,
                worker.resolvedWorkerId(),
                worker.capacity().resolveMentorMax(),
                lease,
                HephTurnDispatcher.STOP_GRACE);
    }

    /**
     * The dispatcher's tick, on the worker's own scheduler: workers run without {@code @EnableScheduling}. The tick
     * stops when this bean is destroyed, after the dispatcher's drain, so leases are renewed while turns drain.
     */
    @Bean
    ScheduledTaskRegistrar hephTurnTicks(HephTurnDispatcher dispatcher) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(dispatcher::tick, HephTurnDispatcher.TICK, HephTurnDispatcher.TICK));
        return tasks;
    }
}
