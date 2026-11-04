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

        int getLearnedLimit();

        int getLimitCredit();

        /** Whether the provider has not refused recently, so that {@link LlmCallLimit} may rise quickly. */
        boolean getProbing();

        /** When the limit last fell, as an opaque mark: a permit keeps the one it started under. */
        @Nullable
        Long getThrottleMark();

        boolean getDedicated();

        @Nullable
        Integer getBaselineFirstTokenMs();

        @Nullable
        Integer getWindowFirstTokenMs();

        int getWindowSamples();

        @Nullable
        Integer getRequestsPerMinute();

        @Nullable
        Long getTokensPerMinute();

        @Nullable
        Long getTokensPerCall();

        /** Milliseconds until the pool's cooldown ends; 0 without one. */
        long getCooldownMillis();
    }

    @Query(
            value = "SELECT slug AS \"slug\", max_concurrent_calls AS \"maxConcurrentCalls\","
                    + " learned_limit AS \"learnedLimit\", limit_credit AS \"limitCredit\", dedicated AS \"dedicated\","
                    + " baseline_first_token_ms AS \"baselineFirstTokenMs\", window_first_token_ms AS \"windowFirstTokenMs\","
                    + " window_samples AS \"windowSamples\","
                    + " throttled_at IS NULL OR throttled_at < clock_timestamp() - interval '10 minutes' AS \"probing\","
                    + " CAST(EXTRACT(EPOCH FROM throttled_at) * 1000000 AS BIGINT) AS \"throttleMark\","
                    + " requests_per_minute AS \"requestsPerMinute\", tokens_per_minute AS \"tokensPerMinute\", tokens_per_call AS \"tokensPerCall\","
                    + " CAST(GREATEST(0, CEIL(EXTRACT(EPOCH FROM (no_call_before - clock_timestamp())) * 1000)) AS BIGINT)"
                    + " AS \"cooldownMillis\""
                    + " FROM llm_call_pool WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<Admission> lockAdmission(@Param("id") long id);

    /** Extends the pool's cooldown to at least {@code millis} from now; a later cooldown stays. */
    @Modifying
    @Query(
            value = "UPDATE llm_call_pool SET no_call_before = GREATEST(no_call_before,"
                    + " clock_timestamp() + make_interval(secs => :millis / 1000.0)) WHERE id = :id",
            nativeQuery = true)
    int coolDown(@Param("id") long id, @Param("millis") long millis);

    /** Writes what the provider taught; the caller holds the pool row lock. */
    @Modifying
    @Query(
            value = "UPDATE llm_call_pool SET learned_limit = :limit, limit_credit = :credit WHERE id = :id",
            nativeQuery = true)
    int learned(@Param("id") long id, @Param("limit") int limit, @Param("credit") int credit);

    /** Lowers the limit and starts a new mark; the caller holds the pool row lock. */
    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_pool SET learned_limit = :limit, limit_credit = 0,"
                            + " window_first_token_ms = NULL, window_samples = 0, throttled_at = clock_timestamp() WHERE id = :id",
            nativeQuery = true)
    int throttled(@Param("id") long id, @Param("limit") int limit);

    /** Writes what first tokens taught; the caller holds the pool row lock. */
    @Modifying
    @Query(
            value = "UPDATE llm_call_pool SET baseline_first_token_ms = :baseline, window_first_token_ms = :window,"
                    + " window_samples = :samples WHERE id = :id",
            nativeQuery = true)
    int firstTokens(
            @Param("id") long id,
            @Param("baseline") @Nullable Integer baseline,
            @Param("window") @Nullable Integer window,
            @Param("samples") int samples);

    /** Serialize rare membership edits without taking admission locks on unrelated pools. */
    @Query(value = "SELECT true FROM pg_advisory_xact_lock(728193463)", nativeQuery = true)
    boolean lockMembership();
}
