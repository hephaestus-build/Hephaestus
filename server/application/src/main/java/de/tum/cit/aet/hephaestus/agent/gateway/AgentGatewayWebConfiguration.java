package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The {@link SandboxRun} argument of the agent routes, where the gateway serves sandboxes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@Profile("!specs & !cds-training")
class AgentGatewayWebConfiguration implements WebMvcConfigurer {

    private final AgentRuns runs;

    AgentGatewayWebConfiguration(AgentRuns runs) {
        this.runs = runs;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new SandboxRunArgumentResolver(runs));
    }
}
