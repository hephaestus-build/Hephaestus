package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A provider throttle pauses one credential generation. Every server checks the generation before a provider read.
 */
@Entity
@Table(name = "provider_credential_pause")
@Getter
@Setter
@NoArgsConstructor
public class ProviderCredentialPause {

    @Id
    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "credentials_generation", nullable = false)
    private long credentialsGeneration;

    @Column(name = "paused_until", nullable = false)
    private Instant pausedUntil;
}
