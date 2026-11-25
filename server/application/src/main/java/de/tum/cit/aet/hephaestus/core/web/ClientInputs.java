package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private static final Duration KEY_AGE = Duration.ofDays(3);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final OwnerScope owners;
    private final ObjectMapper json;
    private final Clock clock;

    /** The resource ids needed to read the response again with current authorization. */
    public record Result(UUID id, UUID relatedId) {}

    @Transactional
    public Result create(
            long workspace,
            PersonAct person,
            UUID key,
            String operation,
            Object body,
            List<String> attachmentHashes,
            Supplier<Result> create) {
        validateAge(key);
        long account = owners.fromPrincipal(person);
        byte[] fingerprint = fingerprint(operation, body, attachmentHashes);
        String lock = "client-input:" + workspace + ":" + account + ":" + key;
        boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", lock)
                .query(Boolean.class)
                .single();
        if (!acquired) {
            var status = HttpStatus.CONFLICT;
            throw new ErrorResponseException(
                    status,
                    ProblemType.IDEMPOTENCY_KEY_IN_FLIGHT.problem(
                            status, "Still sending", "Wait a moment, then try again."),
                    null);
        }
        var stored = jdbc.sql("""
            SELECT fingerprint,result_id,related_id FROM client_input
            WHERE workspace_id=:workspace AND account_id=:account AND idem_key=:key
            """)
                .param("workspace", workspace)
                .param("account", account)
                .param("key", key)
                .query((row, index) -> {
                    if (!MessageDigest.isEqual(row.getBytes("fingerprint"), fingerprint)) {
                        throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT);
                    }
                    return new Result(row.getObject("result_id", UUID.class), row.getObject("related_id", UUID.class));
                })
                .optional();
        if (stored.isPresent()) {
            return stored.get();
        }
        Result result = create.get();
        jdbc.sql("""
            INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id)
            VALUES(:workspace,:account,:key,:fingerprint,:result,:related)
            """)
                .param("workspace", workspace)
                .param("account", account)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("result", result.id())
                .param("related", result.relatedId())
                .update();
        return result;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "client-input-cleanup", lockAtMostFor = "PT5M")
    @Transactional
    public void prune() {
        jdbc.sql("SELECT public.prune_client_inputs()").query(Integer.class).single();
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

    byte[] fingerprint(String operation, Object body, List<String> attachmentHashes) {
        // Each create supplies a typed body, so JSON field order from the request does not reach the digest.
        byte[] bytes = json.writeValueAsBytes(List.of(operation, body, attachmentHashes));
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
