package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.Credential;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.Observations;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.RepositoryReads;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stores an authenticated provider observation, including its access decision and shared metadata. */
@Service
public class RepositoryObservationStore implements Observations {

    public enum Kind {
        PUBLIC,
        PRIVATE,
        NOT_FOUND,
        METADATA
    }

    public record Observation(
            Kind kind,
            Repository.@Nullable Visibility visibility,
            @Nullable Map<String, @Nullable Object> patch,
            boolean deleteMirror) {
        private Observation(
                Kind kind, Repository.@Nullable Visibility visibility, @Nullable Map<String, @Nullable Object> patch) {
            this(kind, visibility, patch, false);
        }

        public static Observation deleted() {
            return new Observation(Kind.PRIVATE, Repository.Visibility.PRIVATE, null, true);
        }

        public static Observation metadata(Map<String, @Nullable Object> patch) {
            return new Observation(Kind.METADATA, null, patch);
        }

        public static Observation read(
                ReadersRead read, Repository.Visibility visibility, Map<String, @Nullable Object> patch) {
            if (visibility != Repository.Visibility.PUBLIC) {
                return new Observation(Kind.PRIVATE, visibility, patch);
            }
            return read.syncTargetId() == null ? metadata(patch) : new Observation(Kind.PUBLIC, visibility, patch);
        }

        public static Observation privateRepository() {
            return new Observation(Kind.PRIVATE, Repository.Visibility.PRIVATE, null);
        }

        public static Observation notFound() {
            return new Observation(Kind.NOT_FOUND, null, null);
        }
    }

    /** A stale path can accompany a committed access restriction. Callers must not reconcile that path. */
    public record Result(boolean applied, @Nullable Repository repository) {}

    private final RepositoryObservationQueries queries;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ObjectMapper mapper;

    RepositoryObservationStore(RepositoryObservationQueries queries, ObjectMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    public static Map<String, @Nullable Object> path(String fullName, String name, String url) {
        var patch = new HashMap<String, @Nullable Object>();
        patch.put("name_with_owner", fullName);
        patch.put("name", name);
        patch.put("html_url", url);
        return patch;
    }

    /** This authority is only for a verified provider event, which cannot establish a public proof. */
    public static ReadersRead providerEvent(long provider, long nativeId, Instant receivedAt) {
        return new ReadersRead(0L, null, nativeId, provider, -1L, receivedAt);
    }

    public Instant databaseTime() {
        return queries.databaseTime();
    }

    @Override
    @Transactional(readOnly = true)
    public RepositoryReads capture(long workspace, long provider, @Nullable Long monitor) {
        var snapshots = queries.capture(workspace, provider, monitor);
        if (snapshots.isEmpty()) return new RepositoryReads(workspace, provider, Instant.EPOCH, Map.of());
        var first = snapshots.getFirst();
        Long connection = first.getConnectionId();
        var credential = connection == null
                ? null
                : new Credential(connection, first.getCredentialsGeneration(), first.getLifecycleGeneration());
        var reads = new HashMap<Long, ReadersRead>();
        for (var snapshot : snapshots) {
            Long nativeId = snapshot.getNativeId();
            if (snapshot.getMonitorId() == null || nativeId == null) continue;
            reads.putIfAbsent(
                    nativeId,
                    new ReadersRead(
                            workspace,
                            snapshot.getMonitorId(),
                            nativeId,
                            provider,
                            snapshot.getProofGeneration(),
                            first.getObservedAt(),
                            credential));
        }
        return new RepositoryReads(workspace, provider, first.getObservedAt(), Map.copyOf(reads), credential);
    }

    @Override
    @Transactional
    public void recordLoss(ReadersRead read) {
        apply(read, Observation.notFound());
    }

    @Transactional
    public Result observe(ReadersRead read, Observation observation) {
        return apply(read, observation);
    }

    /** Narrows a known mirror after a public check that the caller verified under the connection lifecycle lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restrictPublicAccess(long providerId, long repositoryId, Instant checkedAt) {
        queries.restrictPublicAccess(providerId, repositoryId, checkedAt);
    }

    private Result apply(ReadersRead read, Observation observation) {
        if (read.credential() == null && read.scopeId() != 0) return new Result(false, null);
        var entities = Objects.requireNonNull(entityManager);
        entities.flush();
        long id = queries.applyObservation(
                read,
                observation.kind().name(),
                observation.visibility() == null
                        ? null
                        : observation.visibility().name(),
                observation.patch() == null ? null : mapper.writeValueAsString(observation.patch()));
        if (id <= 0) return new Result(id == 0, null);
        var repository = entities.find(Repository.class, id);
        // A caller may already have loaded this mirror in the same accumulation transaction.
        if (repository != null) {
            entities.refresh(repository);
            if (observation.deleteMirror()) entities.remove(repository);
        }
        return new Result(true, repository);
    }
}
