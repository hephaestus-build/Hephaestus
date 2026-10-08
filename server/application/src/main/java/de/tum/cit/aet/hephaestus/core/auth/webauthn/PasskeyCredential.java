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
import lombok.Setter;

@Entity
@Table(
        name = "passkey_credential",
        indexes = {@Index(name = "ix_passkey_credential_account", columnList = "account_id")})
@Getter
@Setter
@NoArgsConstructor
public class PasskeyCredential {
    @Id
    @Column(length = 2048)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "fk_passkey_credential_account"))
    private Account account;

    @Column(name = "record_json", nullable = false, columnDefinition = "text")
    private String recordJson;

    public PasskeyCredential(String id, Account account, String recordJson) {
        this.id = id;
        this.account = account;
        this.recordJson = recordJson;
    }
}
