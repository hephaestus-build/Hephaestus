package de.tum.cit.aet.hephaestus.agent.attempt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The workers' public keys, on the database clock, so that worker clocks never decide which keys are live. */
@Repository
@WorkspaceAgnostic("Fleet-wide verification keys; a token's workspace is a signed claim.")
interface AttemptPublicKeyRepository extends JpaRepository<AttemptPublicKey, String> {

    @Transactional
    @Modifying
    @Query(
            value = "INSERT INTO attempt_public_key (kid, public_key, not_after) "
                    + "VALUES (:kid, :publicKey, now() + make_interval(secs => :keptForSeconds)) "
                    + "ON CONFLICT (kid) DO UPDATE SET not_after = EXCLUDED.not_after",
            nativeQuery = true)
    void publish(
            @Param("kid") String kid,
            @Param("publicKey") byte[] publicKey,
            @Param("keptForSeconds") long keptForSeconds);

    @Query(value = "SELECT * FROM attempt_public_key WHERE not_after > now()", nativeQuery = true)
    List<AttemptPublicKey> findLive();

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM attempt_public_key WHERE not_after <= now()", nativeQuery = true)
    int deleteExpired();
}
