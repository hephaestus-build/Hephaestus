package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "passkey_challenge",
        indexes = {
            @Index(name = "ix_passkey_challenge_account", columnList = "account_id"),
            @Index(name = "ix_passkey_challenge_expiry", columnList = "expires_at")
        })
@Getter
@NoArgsConstructor
public class PasskeyChallenge {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "fk_passkey_challenge_account"))
    private Account account;

    @Column(nullable = false)
    private UUID tokenId;

    @Column(nullable = false, length = 16)
    private String purpose;

    @Column(nullable = false, columnDefinition = "text")
    private String optionsJson;

    @Column(nullable = false)
    private Instant expiresAt;

    public PasskeyChallenge(Account account, UUID tokenId, String purpose, String optionsJson, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.account = account;
        this.tokenId = tokenId;
        this.purpose = purpose;
        this.optionsJson = optionsJson;
        this.expiresAt = expiresAt;
    }
}
