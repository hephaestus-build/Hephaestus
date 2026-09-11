package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "consent_decision")
@Getter
@NoArgsConstructor
public class ConsentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", foreignKey = @ForeignKey(name = "fk_consent_decision_account"))
    private @Nullable Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 40)
    private Purpose purpose;

    @Column(name = "granted", nullable = false)
    private boolean granted;

    @Enumerated(EnumType.STRING)
    @Column(name = "mechanism", nullable = false, length = 32)
    private Mechanism mechanism;

    /** Which wording the account was shown — {@link ConsentService#WORDING_VERSION} owns it. */
    @Column(name = "notice_version", nullable = false, length = 32)
    private String noticeVersion;

    /**
     * The organisation the research question named, as it was rendered. Null on every other purpose,
     * and on a research decision only where the instance ran no study.
     *
     * <p>It is here rather than derived from configuration because configuration changes and evidence
     * may not: after a rename, this is the only record of who the account was actually asked about.
     */
    @Column(name = "research_organization", length = 255)
    private @Nullable String researchOrganization;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private @Nullable Instant occurredAt;

    public ConsentDecision(
            Account account,
            Purpose purpose,
            boolean granted,
            Mechanism mechanism,
            String noticeVersion,
            @Nullable String researchOrganization) {
        this.account = account;
        this.purpose = purpose;
        this.granted = granted;
        this.mechanism = mechanism;
        this.noticeVersion = noticeVersion;
        this.researchOrganization = researchOrganization;
    }

    public enum Purpose {
        TERMS_ACCEPTANCE,
        PRIVACY_NOTICE_ACKNOWLEDGEMENT,
        RESEARCH_PARTICIPATION,
    }

    public enum Mechanism {
        FIRST_LOGIN_INTERSTITIAL,
        ACCOUNT_SETTINGS,
    }
}
