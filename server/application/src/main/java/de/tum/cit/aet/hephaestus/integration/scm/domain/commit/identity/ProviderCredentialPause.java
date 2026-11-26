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
 * A provider limits the credential of one scope, its workspace, until {@code pausedUntil}. Every server reads it before
 * it asks the provider with that credential, so a throttle costs one request, and other credentials continue.
 */
@Entity
@Table(name = "provider_credential_pause")
@Getter
@Setter
@NoArgsConstructor
public class ProviderCredentialPause {

    @Id
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "paused_until", nullable = false)
    private Instant pausedUntil;
}
