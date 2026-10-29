package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Instance LLM model catalog is global (app_admin-owned), not tenant-scoped.")
public interface LlmModelRepository extends JpaRepository<LlmModel, Long> {
    Optional<LlmModel> findByConnectionIdAndSlug(Long connectionId, String slug);

    boolean existsByConnectionIdAndUpstreamModelId(Long connectionId, String upstreamModelId);

    /**
     * Every instance model exposing this upstream id. Deliberately a list: uniqueness is only enforced
     * per connection, so two connections may serve the same upstream id at different prices, and the one
     * caller that resolves a model from a stored name ({@code LlmUsageRepricer}) must be able to see that
     * it is ambiguous rather than be handed an arbitrary winner.
     */
    List<LlmModel> findByUpstreamModelId(String upstreamModelId);

    boolean existsByConnectionId(Long connectionId);

    /** Scalar selection bypasses a model already cached by a claim transaction. */
    @Query("SELECT m.callPoolId FROM LlmModel m WHERE m.id = :id AND m.connection.id = :connectionId")
    Optional<Long> findCallPoolId(@Param("id") Long id, @Param("connectionId") Long connectionId);

    @Query("SELECT m FROM LlmModel m JOIN FETCH m.connection ORDER BY m.id")
    List<LlmModel> findAllWithConnection();

    /**
     * {@code connection} is fetched eagerly because callers map the entity to a DTO after the
     * transaction closes and OSIV is off.
     */
    @Query("SELECT m FROM LlmModel m JOIN FETCH m.connection WHERE m.id = :id")
    Optional<LlmModel> findByIdWithConnection(@Param("id") Long id);

    /** Serializes activation and repricing so an enabled model can never end a race unpriced. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM LlmModel m JOIN FETCH m.connection WHERE m.id = :id")
    Optional<LlmModel> findByIdForUpdate(@Param("id") Long id);

    /**
     * Both {@code llm_model} and {@code llm_model_workspace_grant} are global tables, so
     * {@code :workspaceId} is a plain filter here, not a tenancy predicate.
     */
    @Query("SELECT m FROM LlmModel m JOIN FETCH m.connection c " + "WHERE m.enabled = true AND c.enabled = true "
            + "AND (m.visibility = de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility.PUBLIC "
            + "OR EXISTS (SELECT 1 FROM LlmModelWorkspaceGrant g "
            + "WHERE g.id.modelId = m.id AND g.id.workspaceId = :workspaceId)) "
            + "ORDER BY m.id")
    List<LlmModel> findVisibleEnabledModels(@Param("workspaceId") Long workspaceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LlmModel m SET m.callPoolId = NULL WHERE m.callPoolId = :poolId")
    int leaveCallPool(@Param("poolId") Long poolId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LlmModel m SET m.callPoolId = :poolId WHERE m.id IN :ids")
    int joinCallPool(@Param("poolId") Long poolId, @Param("ids") Collection<Long> ids);
}
