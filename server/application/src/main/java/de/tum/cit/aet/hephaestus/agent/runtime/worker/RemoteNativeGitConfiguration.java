package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerControlWebSocketHandler;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * An application server without the worker role has no Docker and no mirrors; its Git operations go
 * to a connected worker. With the worker role on, the Docker executor is the only one, so this never
 * competes with it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
public class RemoteNativeGitConfiguration {

    @Bean
    @ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "false")
    RemoteNativeGitExecutor remoteNativeGitExecutor(
            WorkerSessionRegistry registry, WorkerControlWebSocketHandler hub, ObjectMapper mapper) {
        return new RemoteNativeGitExecutor(registry, hub, mapper);
    }
}
