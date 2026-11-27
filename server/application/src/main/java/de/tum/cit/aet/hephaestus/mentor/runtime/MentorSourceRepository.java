package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The SQL of the mentor's rows, which are account-owned under row-level security: each statement names the account or
 * runs as the owner. Only {@link MentorIntake} writes them; the locks are {@code NOWAIT}, so a held row fails the
 * caller's transaction instead of waiting.
 */
@WorkspaceAgnostic("The mentor's rows are account-owned; row-level security scopes them to the bound account")
public interface MentorSourceRepository extends Repository<MentorSource, UUID> {
    /** Every account's rows of the workspace, locking the sources first as intake does. Returns the sources erased. */
    @Query(value = "SELECT public.mentor_erase_workspace(:workspaceId)", nativeQuery = true)
    int eraseWorkspace(@Param("workspaceId") long workspaceId);

    /** The owner of one canonical message, before any owner is bound. */
    @Query(value = "SELECT public.mentor_source_owner(:binding, :container, :message)", nativeQuery = true)
    @Nullable
    Long owner(@Param("binding") long binding, @Param("container") String container, @Param("message") String message);

    @Modifying
    @Query(value = """
            INSERT INTO mentor_source(id, account_id, binding_id, workspace_id, container_id, native_message_id, producer,
                revision, state)
            VALUES (:#{#s.id()}, :#{#s.account()}, :#{#s.binding()}, :#{#s.workspace()}, :#{#s.container()},
                :#{#s.message()}, :#{#s.producer()}, :#{#s.revision()}, 'LIVE')
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertSource(@Param("s") NewSource s);

    @Query(value = """
            SELECT s.id AS id, s.producer AS producer, s.state AS state, s.revision AS revision,
                (SELECT r.id FROM mentor_request r WHERE r.source_id = s.id AND r.account_id = s.account_id) AS request
            FROM mentor_source s
            WHERE s.account_id = :account AND s.binding_id = :binding AND s.container_id = :container
                AND s.native_message_id = :message
            FOR UPDATE OF s NOWAIT
            """, nativeQuery = true)
    Optional<LockedSource> lockSource(
            @Param("account") long account,
            @Param("binding") long binding,
            @Param("container") String container,
            @Param("message") String message);

    @Query(value = """
            SELECT r.state AS state, r.version AS version FROM mentor_request r
            WHERE r.id = :id AND r.account_id = :account
            FOR UPDATE NOWAIT
            """, nativeQuery = true)
    LockedRequest lockRequest(@Param("id") UUID id, @Param("account") long account);

    @Modifying
    @Query(value = """
            INSERT INTO mentor_event(id, account_id, binding_id, native_event_id, kind, source_id, request_id,
                disposition, reason, source_ref, occurred_at, received_at)
            VALUES (:#{#e.id()}, :#{#e.account()}, :#{#e.binding()}, :#{#e.event()}, :#{#e.kind()}, :#{#e.source()},
                :#{#e.request()}, :#{#e.disposition()}, :#{#e.reason()}, :#{#e.sourceRef()}, :#{#e.occurred()},
                :#{#e.received()})
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertEvent(@Param("e") NewEvent e);

    /** Opens version 1. The authority columns are those of the stamp's {@code AuthorityScope}; the others are NULL. */
    @Modifying
    @Query(value = """
            INSERT INTO mentor_request(id, account_id, source_id, version, state, audience_transport,
                audience_connection_id, audience_container_id, audience_thread_root, scope_account_generation,
                scope_reader_epoch, scope_consent_epoch, scope_authority, scope_workspace_id, scope_workspace_generation,
                scope_binding_id, scope_binding_generation, scope_native_actor_id, opened_at, deadline)
            VALUES (:#{#r.id()}, :#{#r.account()}, :#{#r.source()}, 1, 'OPEN', :#{#r.transport()},
                :#{#r.connection()}, :#{#r.container()}, :#{#r.threadRoot()},
                :#{#r.accountGeneration()}, :#{#r.readerEpoch()}, :#{#r.consentEpoch()}, :#{#r.authority()},
                :#{#r.workspace()}, :#{#r.workspaceGeneration()},
                :#{#r.binding()}, :#{#r.bindingGeneration()},
                :#{#r.actor()}, :#{#r.opened()}, :#{#r.deadline()})
            """, nativeQuery = true)
    int openRequest(@Param("r") OpenedRequest r);

    @Modifying
    @Query(
            value = "UPDATE mentor_request SET version = version + 1 WHERE id = :id AND account_id = :account",
            nativeQuery = true)
    int revise(@Param("id") UUID id, @Param("account") long account);

    @Modifying
    @Query(
            value = "UPDATE mentor_request SET state = 'STOPPED' WHERE id = :id AND account_id = :account",
            nativeQuery = true)
    int stop(@Param("id") UUID id, @Param("account") long account);

    @Modifying
    @Query(
            value = "UPDATE mentor_source SET state = 'DELETED' WHERE id = :id AND account_id = :account",
            nativeQuery = true)
    int markDeleted(@Param("id") UUID id, @Param("account") long account);

    @Modifying
    @Query(
            value = "UPDATE mentor_source SET revision = :revision WHERE id = :id AND account_id = :account",
            nativeQuery = true)
    int raiseRevision(@Param("id") UUID id, @Param("account") long account, @Param("revision") String revision);

    @Modifying
    @Query(
            value = "UPDATE mentor_event SET request_id = :request WHERE id = :id AND account_id = :account",
            nativeQuery = true)
    int linkRequest(@Param("id") UUID id, @Param("account") long account, @Param("request") UUID request);

    interface LockedSource {
        UUID getId();

        String getProducer();

        String getState();

        String getRevision();

        @Nullable
        UUID getRequest();
    }

    interface LockedRequest {
        String getState();

        int getVersion();
    }

    record NewSource(
            UUID id,
            long account,
            long binding,
            long workspace,
            String container,
            String message,
            String producer,
            String revision) {}

    record NewEvent(
            UUID id,
            long account,
            long binding,
            String event,
            String kind,
            UUID source,
            @Nullable UUID request,
            String disposition,
            @Nullable String reason,
            String sourceRef,
            Instant occurred,
            Instant received) {}

    record OpenedRequest(
            UUID id,
            long account,
            UUID source,
            String transport,
            @Nullable Long connection,
            String container,
            @Nullable String threadRoot,
            long accountGeneration,
            long readerEpoch,
            long consentEpoch,
            String authority,
            @Nullable Long workspace,
            @Nullable Long workspaceGeneration,
            @Nullable Long binding,
            @Nullable Long bindingGeneration,
            @Nullable String actor,
            Instant opened,
            Instant deadline) {}
}
