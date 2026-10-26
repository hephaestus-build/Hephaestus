package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Instance-owned provider capacity, shared across workspaces.")
public interface LlmCallPoolRepository extends JpaRepository<LlmCallPool, Long> {

    boolean existsBySlug(String slug);

    /** What a permit is decided on, read under the pool row lock, with the cooldown left on the database clock. */
    interface Admission {
        String getSlug();

        @Nullable
        Integer getMaxConcurrentCalls();

        int getInteractiveReserve();

        /** Milliseconds until the pool's cooldown ends; 0 without one. */
        long getCooldownMillis();
    }

    @Query(
            value = "SELECT slug AS \"slug\", max_concurrent_calls AS \"maxConcurrentCalls\","
                    + " interactive_reserve AS \"interactiveReserve\","
                    + " CAST(GREATEST(0, CEIL(EXTRACT(EPOCH FROM (no_call_before - now())) * 1000)) AS BIGINT)"
                    + " AS \"cooldownMillis\""
                    + " FROM llm_call_pool WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<Admission> lockAdmission(@Param("id") long id);

    /** Extends the pool's cooldown to at least {@code millis} from now; a later cooldown stays. */
    @Modifying
    @Query(
            value = "UPDATE llm_call_pool SET no_call_before = GREATEST(no_call_before,"
                    + " now() + make_interval(secs => :millis / 1000.0)) WHERE id = :id",
            nativeQuery = true)
    int coolDown(@Param("id") long id, @Param("millis") long millis);
}
