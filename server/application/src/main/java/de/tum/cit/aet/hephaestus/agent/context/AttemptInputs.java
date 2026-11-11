package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedCheckouts;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What any server needs to rebuild a running attempt's folder that another server captured: the folder's files in
 * {@link ReviewAttemptInput} rows, and the checkouts that the rows leave out.
 */
@Component
public class AttemptInputs {
    private final ReviewAttemptInputRepository rows;
    private final CapturedCheckouts checkouts;
    private final TransactionTemplate transactions;

    public AttemptInputs(
            ReviewAttemptInputRepository rows, CapturedCheckouts checkouts, PlatformTransactionManager transactions) {
        this.rows = rows;
        this.checkouts = checkouts;
        this.transactions = new TransactionTemplate(transactions);
    }

    /**
     * Records the files of the attempt's folder outside its checkouts, once the folder is published. Only the running
     * attempt of the job matches; the reconcile deletes the rows once it ends ({@link #removeEnded}). All files or
     * none: a rebuild never finds part of a folder.
     */
    public void record(UUID jobId, long workspaceId, int attempt, Map<String, Path> files) {
        transactions.executeWithoutResult(status -> {
            for (var file : new TreeMap<>(files).entrySet()) {
                byte[] content;
                String permissions;
                try {
                    content = Files.readAllBytes(file.getValue());
                    permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getValue()));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                int recorded = rows.record(
                        workspaceId,
                        jobId,
                        attempt,
                        new RecordedFile(file.getKey(), ProvenanceDigest.sha256Hex(content), permissions),
                        content);
                // An attempt that no longer runs gets no folder that another server could rebuild: its capture fails.
                if (recorded != 1) throw new IllegalStateException("The attempt no longer runs its job");
            }
        });
    }

    /** One frozen file: its path, content digest and POSIX permissions, including its executable mode. */
    public record RecordedFile(String path, String sha256, String permissions) {}

    /** The recorded files of the job's running attempt; empty when none are recorded, or no longer. */
    public List<RecordedFile> list(ReviewJob job) {
        return rows.findFiles(job.getWorkspaceId(), job.getId(), job.getAttempt(), job.getRunId()).stream()
                .map(file -> new RecordedFile(file.getPath(), file.getSha256(), file.getPermissions()))
                .toList();
    }

    /**
     * The content of one recorded file, read alone so that a rebuild holds one file in memory at a time.
     *
     * @throws CheckoutUnverifiedException when the file was deleted since it was listed, as by an erasure
     */
    public byte[] content(ReviewJob job, String path) {
        byte[] content = rows.findContent(job.getWorkspaceId(), job.getId(), job.getAttempt(), job.getRunId(), path);
        if (content == null)
            throw new CheckoutUnverifiedException(
                    CheckoutUnverifiedException.Reason.UNAVAILABLE, "A recorded file is no longer recorded");
        return content;
    }

    /**
     * Copies the recorded checkout to {@code target}.
     *
     * @throws CheckoutUnverifiedException when this server cannot build it
     */
    public void copyCheckout(long workspaceId, CheckoutKey key, Path target, Predicate<Path> verified) {
        checkouts.copy(workspaceId, key, target, verified);
    }

    /** Deletes the rows of every attempt that is not its job's running attempt; any server may run it. */
    public int removeEnded() {
        return Objects.requireNonNull(transactions.execute(status -> rows.deleteEnded()));
    }
}
