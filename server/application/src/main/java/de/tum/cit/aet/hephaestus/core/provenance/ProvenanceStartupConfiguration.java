package de.tum.cit.aet.hephaestus.core.provenance;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@ConditionalOnServerRole
class ProvenanceStartupConfiguration {

    /**
     * Checks the stored keys first, so a server with another system key never makes a key that the other servers
     * cannot open. It checks again after the bootstrap: another server can make the first key between the two steps,
     * under a system key that this one does not hold. Not caught: a server that cannot mark the text it generates does
     * not start. The build profiles have no database.
     */
    @Bean
    @Profile("!specs & !cds-training")
    ApplicationRunner provenanceKeysOnStartup(ProvenanceKeys keys) {
        return args -> {
            keys.verifyAllOpen();
            keys.ensureSigningKey();
            keys.verifyAllOpen();
        };
    }
}
