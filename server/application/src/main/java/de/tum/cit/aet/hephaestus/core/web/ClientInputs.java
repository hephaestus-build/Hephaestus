package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.database.ClientInputRepository;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.exception.MentoringProblems;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.ObjectMapper;

/** Commits a create and its result reference in the same transaction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ClientInputs {
    public static final String HEADER = "Idempotency-Key";
    private static final int PRUNE_BATCH_SIZE = 1000;
    // A burst drains in one run instead of reporting each full batch as work left behind. After a full last batch, one
    // more batch runs: only when it is full too are receipts left behind. A run removes up to 13 batches.
    private static final int PRUNE_BATCHES_PER_RUN = 12;

    private static final Duration KEY_AGE = Duration.ofDays(3);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final ClientInputRepository receipts;
    private final OwnerScope owners;
    private final PrivacyJobMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;

    /** The resource ids needed to read the response again with current authorization. */
    public record Result(UUID id, UUID relatedId) {}

    public record FingerprintInput(String operation, Object body, List<String> attachmentHashes) {}

    @Transactional
    public Result create(long workspace, PersonAct person, UUID key, FingerprintInput input, Supplier<Result> create) {
        validateAge(key);
        long account = owners.fromPrincipal(person);
        byte[] fingerprint = fingerprint(input);
        if (!receipts.tryLock(workspace, account, key)) {
            throw new ErrorResponseException(HttpStatus.CONFLICT, MentoringProblems.idempotencyKeyInFlight(), null);
        }
        var stored = receipts.find(workspace, account, key, fingerprint);
        if (stored.isPresent()) {
            var receipt = stored.get();
            if (!receipt.fingerprintMatches()) {
                throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT);
            }
            return new Result(receipt.resultId(), receipt.relatedId());
        }
        Result result = create.get();
        receipts.save(workspace, account, key, fingerprint, result.id(), result.relatedId());
        return result;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "client-input-cleanup", lockAtMostFor = "PT5M")
    @WorkspaceAgnostic("Expires create receipts of every owner through the owner function person_row_sweep")
    public void prune() {
        // Each batch commits on its own, so a failed batch keeps the batches before it.
        long removed = 0;
        try {
            int batch = PRUNE_BATCH_SIZE;
            for (int run = 0; run < PRUNE_BATCHES_PER_RUN && batch == PRUNE_BATCH_SIZE; run++) {
                batch = receipts.prune();
                removed += batch;
            }
            int left = batch == PRUNE_BATCH_SIZE ? receipts.prune() : 0;
            removed += left;
            metrics.record(Job.CLIENT_INPUT_RETENTION, left == PRUNE_BATCH_SIZE ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            metrics.recordAffected(Job.CLIENT_INPUT_RETENTION, removed);
        } catch (RuntimeException exception) {
            metrics.recordAffected(Job.CLIENT_INPUT_RETENTION, removed);
            metrics.record(Job.CLIENT_INPUT_RETENTION, Outcome.FAILURE);
            throw exception;
        }
    }

    void validateAge(UUID key) {
        if (key.version() != 7 || key.variant() != 2) {
            throw new InvalidInputException("Idempotency-Key must be a version 7 UUID.");
        }
        Instant created = Instant.ofEpochMilli(key.getMostSignificantBits() >>> 16);
        Instant now = clock.instant();
        if (created.isAfter(now.plus(CLOCK_SKEW))) {
            throw new InvalidInputException("Check your device's time and try again.");
        }
        if (created.isBefore(now.minus(KEY_AGE))) {
            throw new InvalidInputException("This request is too old to retry. Send it again as a new request.");
        }
    }

    byte[] fingerprint(FingerprintInput input) {
        // Each create supplies a typed body, so JSON field order from the request does not reach the digest.
        byte[] bytes = json.writeValueAsBytes(List.of(input.operation(), input.body(), input.attachmentHashes()));
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
