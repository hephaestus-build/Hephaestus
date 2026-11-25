package de.tum.cit.aet.hephaestus.core.web;

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
import org.springframework.http.HttpStatus;
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

    private static final Duration KEY_AGE = Duration.ofDays(3);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final ClientInputRepository receipts;
    private final OwnerScope owners;
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
