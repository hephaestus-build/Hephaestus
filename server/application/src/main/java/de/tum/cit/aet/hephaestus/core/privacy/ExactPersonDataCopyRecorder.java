package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A capture's provenance travels with its bytes, not with a later lookup of mutable source authorship. */
@Component
@WorkspaceAgnostic("Only immutable user/provider keys are read; each producer owns its workspace-scoped content read")
public class ExactPersonDataCopyRecorder implements PersonDataCopyRecorder {
    private final JdbcTemplate jdbc;
    private static final ThreadLocal<CaptureFrame> ACTIVE = new ThreadLocal<>();

    public ExactPersonDataCopyRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordUser(long userId) {
        var frame = ACTIVE.get();
        if (frame == null) return;
        frame.users
                .computeIfAbsent(
                        userId,
                        key -> jdbc.query(
                                "SELECT p.type,p.server_url,u.native_id::text FROM \"user\" u JOIN identity_provider p ON p.id=u.provider_id WHERE u.id=?",
                                (rs, row) -> new PersonCopyIdentity(
                                        Objects.requireNonNull(rs.getString(1)),
                                        Objects.requireNonNull(rs.getString(2)),
                                        Objects.requireNonNull(rs.getString(3)),
                                        null),
                                key))
                .forEach(this::recordIdentity);
    }

    @Override
    public void recordIdentity(PersonCopyIdentity identity) {
        for (var frame = ACTIVE.get(); frame != null; frame = frame.parent) {
            if (frame.identities.add(identity) && frame.writer != null) frame.writer.run();
        }
    }

    @Override
    public void recordRepository(long repositoryId) {
        if (repositoryId <= 0) throw new IllegalArgumentException("A copied repository requires its exact row key");
        for (var frame = ACTIVE.get(); frame != null; frame = frame.parent) {
            if (frame.repositories.add(repositoryId) && frame.writer != null) frame.writer.run();
        }
    }

    @Override
    public Capture begin() {
        var frame = new CaptureFrame(ACTIVE.get());
        ACTIVE.set(frame);
        return frame;
    }

    private static final class CaptureFrame implements Capture {
        private final @Nullable CaptureFrame parent;
        private final Set<PersonCopyIdentity> identities = new LinkedHashSet<>();
        private final Set<Long> repositories = new LinkedHashSet<>();
        private final Map<Long, List<PersonCopyIdentity>> users;
        private @Nullable Runnable writer;
        private boolean closed;

        private CaptureFrame(@Nullable CaptureFrame parent) {
            this.parent = parent;
            users = parent == null ? new HashMap<>() : parent.users;
        }

        @Override
        public List<PersonCopyIdentity> identities() {
            return identities.stream()
                    .sorted(Comparator.comparing(PersonCopyIdentity::providerType)
                            .thenComparing(PersonCopyIdentity::providerOrigin)
                            .thenComparing(PersonCopyIdentity::subject)
                            .thenComparing(identity -> Objects.requireNonNullElse(identity.teamId(), "")))
                    .toList();
        }

        @Override
        public List<Long> repositoryIds() {
            return repositories.stream().sorted().toList();
        }

        @Override
        public void onChange(Runnable writer) {
            this.writer = writer;
        }

        @Override
        public void close() {
            if (closed) return;
            if (ACTIVE.get() != this)
                throw new IllegalStateException("Copy captures must close in their nesting order");
            closed = true;
            if (parent == null) ACTIVE.remove();
            else ACTIVE.set(parent);
        }
    }
}
