package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Slack signs each request with the one signing secret of the instance's Slack app")
public interface SlackRequestClaimRepository extends JpaRepository<SlackRequestClaim, String> {

    /** Claims {@code signature}; 0 when an earlier request already claimed it. */
    @Modifying
    @Query(value = """
        INSERT INTO slack_request_claim (signature, claimed_at) VALUES (:signature, :claimedAt)
        ON CONFLICT (signature) DO NOTHING
        """, nativeQuery = true)
    int claim(String signature, Instant claimedAt);

    @Modifying
    @Query("DELETE FROM SlackRequestClaim c WHERE c.claimedAt < :cutoff")
    int deleteClaimedBefore(Instant cutoff);
}
