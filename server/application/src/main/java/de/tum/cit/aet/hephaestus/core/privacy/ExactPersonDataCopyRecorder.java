package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A capture's provenance travels with its bytes, not with a later lookup of mutable source
 * authorship.
 *
 * <p>It reads only immutable user and provider keys. Each producer owns its workspace-scoped content read.
 */
@Component
public class ExactPersonDataCopyRecorder implements PersonDataCopyRecorder {
    private static final ThreadLocal<CaptureFrame> ACTIVE = new ThreadLocal<>();

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
    public void recordCopiedJob(UUID jobId) {
        for (var frame = ACTIVE.get(); frame != null; frame = frame.parent) {
            if (frame.copiedJobs.add(jobId) && frame.writer != null) frame.writer.run();
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
        private final Set<UUID> copiedJobs = new LinkedHashSet<>();
        private @Nullable Runnable writer;
        private boolean closed;

        private CaptureFrame(@Nullable CaptureFrame parent) {
            this.parent = parent;
        }

        @Override
        public List<PersonCopyIdentity> identities() {
            return identities.stream()
                    .sorted(Comparator.comparing(PersonCopyIdentity::providerType)
                            .thenComparing(PersonCopyIdentity::providerOrigin)
                            .thenComparing(PersonCopyIdentity::subject)
                            .thenComparing(identity -> PersonIdentity.teamKey(identity.teamId())))
                    .toList();
        }

        @Override
        public List<Long> repositoryIds() {
            return repositories.stream().sorted().toList();
        }

        @Override
        public List<UUID> copiedJobIds() {
            return copiedJobs.stream().sorted().toList();
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
