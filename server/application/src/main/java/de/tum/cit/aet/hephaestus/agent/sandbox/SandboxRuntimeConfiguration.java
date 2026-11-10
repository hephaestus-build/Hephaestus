package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The worker's review workspaces: the archives that a review sandbox downloads. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxRuntimeConfiguration {

    @Bean
    public SandboxWorkspaceManager sandboxWorkspaceManager(FabricLayout layout) {
        return new SandboxWorkspaceManager(layout.root());
    }
}
