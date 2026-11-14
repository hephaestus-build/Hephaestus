package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A signed Slack interactivity request that the server already handled, kept while its signature stays valid. */
@Entity
@Table(
        name = "slack_request_claim",
        indexes = @Index(name = "ix_slack_request_claim_claimed_at", columnList = "claimed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackRequestClaim {

    @Id
    @Column(name = "signature", length = 80)
    private String signature;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;
}
