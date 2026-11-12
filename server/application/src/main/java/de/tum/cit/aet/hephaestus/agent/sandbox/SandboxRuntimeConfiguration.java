package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The server's review workspaces: the archives that a review sandbox downloads. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnAgentRuntime
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxRuntimeConfiguration {

    @Bean
    public SandboxWorkspaceManager sandboxWorkspaceManager() {
        return new SandboxWorkspaceManager();
    }
}
