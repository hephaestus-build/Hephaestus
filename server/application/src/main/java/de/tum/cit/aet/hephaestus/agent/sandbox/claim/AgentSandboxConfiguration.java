package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sandbox owner where the sandbox namespace's Kubernetes client is: today the worker role. The server role takes
 * it when the worker folds into the server (ADR 0079 § Runtime components).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
public class AgentSandboxConfiguration {

    @Bean
    AgentSandboxes agentSandboxes(
            AgentSandboxRepository sandboxes,
            AgentRuns runs,
            SandboxClaims claims,
            AttemptTokenIssuer tokens,
            TransactionTemplate transactions,
            AgentSandboxProperties properties) {
        return new AgentSandboxes(
                sandboxes,
                runs,
                claims,
                tokens,
                transactions,
                properties.gatewayUrl(),
                AgentSandboxConfiguration::address);
    }

    @Bean
    AgentSandboxReaper agentSandboxReaper(
            AgentSandboxRepository sandboxes,
            AgentSandboxes owner,
            SandboxClaims claims,
            AgentCapacityProperties capacity,
            TransactionTemplate transactions,
            MeterRegistry meters) {
        return new AgentSandboxReaper(sandboxes, owner, claims, capacity, transactions, meters);
    }

    /**
     * Its own scheduler thread: the worker role does not enable {@code @Scheduled}. The reaper ticks as often as a
     * holder renews its lease, the capacity home's one period for taking work back.
     */
    @Bean
    @Profile("!test")
    ScheduledTaskRegistrar agentSandboxReaperTask(AgentSandboxReaper reaper, AgentCapacityProperties capacity) {
        var tasks = new ScheduledTaskRegistrar();
        Duration period = capacity.leaseRenewal();
        tasks.addFixedDelayTask(new FixedDelayTask(reaper::reap, period, period));
        return tasks;
    }

    private static String address(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("The sandbox gateway host does not resolve", e);
        }
    }
}
