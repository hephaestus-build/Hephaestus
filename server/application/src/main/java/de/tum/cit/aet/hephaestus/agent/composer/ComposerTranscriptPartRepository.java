package de.tum.cit.aet.hephaestus.agent.composer;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Each write is one conditional statement, so two replicas that see the same part change it once. */
interface ComposerTranscriptPartRepository extends JpaRepository<ComposerTranscriptPart, UUID> {

    /** A part of a claim's transcript, other than a tool call. */
    interface KnownPart {
        String getKind();

        String getDigest();

        /** The destination of a session; null for every other part. */
        @Nullable
        String getDestination();
    }

    /** Adds a part of the claim's transcript, once. */
    @Modifying
    @Query(value = """
            INSERT INTO composer_transcript_part (id, workspace_id, run_id, lease_epoch, kind, digest, destination)
            SELECT gen_random_uuid(), :workspaceId, :runId, :epoch, :kind, :digest, CAST(:destination AS varchar)
             WHERE NOT EXISTS (SELECT 1 FROM composer_transcript_part
                                WHERE workspace_id = :workspaceId AND run_id = :runId AND lease_epoch = :epoch
                                  AND kind = :kind AND digest = :digest)""", nativeQuery = true)
    int add(
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("epoch") int epoch,
            @Param("kind") String kind,
            @Param("digest") String digest,
            @Param("destination") @Nullable String destination);

    /** Adds a tool call that the model wrote; a call it wrote twice gets two rows, each used once. */
    @Modifying
    @Query(value = """
            INSERT INTO composer_transcript_part (id, workspace_id, run_id, lease_epoch, kind, digest, call_id, tool)
            VALUES (gen_random_uuid(), :workspaceId, :runId, :epoch, 'CALL', :digest, :callId, :tool)""", nativeQuery = true)
    int addCall(
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("epoch") int epoch,
            @Param("callId") String callId,
            @Param("tool") String tool,
            @Param("digest") String digest);

    /** Uses one unused tool call of the claim that matches exactly; 1 when there was one. */
    @Modifying
    @Query(value = """
            UPDATE composer_transcript_part SET used_at = now()
             WHERE workspace_id = :workspaceId
               AND id = (SELECT id FROM composer_transcript_part
                          WHERE workspace_id = :workspaceId AND run_id = :runId AND lease_epoch = :epoch
                            AND kind = 'CALL' AND digest = :digest AND call_id = :callId AND tool = :tool
                            AND used_at IS NULL
                          ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)""", nativeQuery = true)
    int useCall(
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("epoch") int epoch,
            @Param("callId") String callId,
            @Param("tool") String tool,
            @Param("digest") String digest);

    /** The claim's parts other than tool calls. */
    @Query(value = """
            SELECT kind, digest, destination FROM composer_transcript_part
             WHERE workspace_id = :workspaceId AND run_id = :runId AND lease_epoch = :epoch AND kind <> 'CALL'""", nativeQuery = true)
    List<KnownPart> known(
            @Param("workspaceId") long workspaceId, @Param("runId") UUID runId, @Param("epoch") int epoch);
}
