package de.tum.cit.aet.hephaestus.core.runtime.hub;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtHandshakeInterceptor;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the worker hub: WSS endpoint registration, JWT auth, session registry. Gated by
 * {@link RuntimeRole#SERVER_PROPERTY} with {@code matchIfMissing=true} — the application-server
 * runtime role hosts the hub; webhook and worker pods do not.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@EnableWebSocket
public class HubConfiguration {

    @Bean
    @ConditionalOnMissingBean(FrameCodec.class)
    FrameCodec frameCodec(ObjectMapper objectMapper) {
        return new FrameCodec(objectMapper);
    }

    @Bean
    WorkerSessionRegistry workerSessionRegistry(ApplicationEventPublisher events, MeterRegistry meterRegistry) {
        return new WorkerSessionRegistry(events, meterRegistry);
    }

    @Bean
    WorkerJobCancelDispatcher workerJobCancelDispatcher(WorkerSessionRegistry registry) {
        return new WorkerJobCancelDispatcher(registry);
    }

    @Bean
    WorkerJwtHandshakeInterceptor workerJwtHandshakeInterceptor(WorkerJwtVerifier verifier) {
        return new WorkerJwtHandshakeInterceptor(verifier);
    }

    @Bean
    WorkerControlWebSocketHandler workerControlWebSocketHandler(
            WorkerSessionRegistry registry, FrameCodec codec, MeterRegistry meterRegistry) {
        return new WorkerControlWebSocketHandler(registry, codec, meterRegistry);
    }

    @Bean
    HubWebSocketRegistration hubWebSocketRegistration(
            WorkerControlWebSocketHandler handler, WorkerJwtHandshakeInterceptor interceptor) {
        return new HubWebSocketRegistration(handler, interceptor);
    }
}
