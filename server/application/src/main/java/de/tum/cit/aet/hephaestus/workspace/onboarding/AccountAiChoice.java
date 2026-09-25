package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One account's own, reversible AI choice. It is answered once and holds in every workspace the
 * account is a member of on this instance, because the answer is a ceiling over data-handling tiers
 * rather than a pick from any workspace's models; a row exists only once the person has answered.
 */
@Entity
@Table(name = "account_ai_choice")
@Getter
@Setter
class AccountAiChoice {
    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_choice", nullable = false, length = 24)
    private MemberAiChoice aiChoice;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
