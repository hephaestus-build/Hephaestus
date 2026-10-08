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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "passkey_recovery_code",
        indexes = {@Index(name = "ix_passkey_recovery_code_account", columnList = "account_id")})
@Getter
@NoArgsConstructor
public class PasskeyRecoveryCode {
    @Id
    @Column(length = 64)
    private String hash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "account_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_passkey_recovery_code_account"))
    private Account account;

    public PasskeyRecoveryCode(String hash, Account account) {
        this.hash = hash;
        this.account = account;
    }
}
