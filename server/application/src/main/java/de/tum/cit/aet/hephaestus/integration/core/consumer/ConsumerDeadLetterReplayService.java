package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.InstallationReplayAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.MessageInfo;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.impl.NatsMessage;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterReplayService {
    private static final Logger log = LoggerFactory.getLogger(ConsumerDeadLetterReplayService.class);
    private final ConsumerDeadLetterRepository repository;
    private final IntegrationNatsConsumer consumer;
    private final TransactionTemplate transactions;
    private final InstallationReplayAudit audit;
    private final DataSource dataSource;

    /**
     * One replay of a consumer at a time, on every server: the resume of one replay's fence would end the pause of
     * another. A session lock ends with its connection, so a replay on a server that dies leaves no lock behind.
     */
    private static final String LOCK = "SELECT pg_try_advisory_lock(hashtext('consumer-replay'), hashtext(?))";

    private static final String UNLOCK = "SELECT pg_advisory_unlock(hashtext('consumer-replay'), hashtext(?))";

    public ConsumerDeadLetterReplayService(
            ConsumerDeadLetterRepository repository,
            IntegrationNatsConsumer consumer,
            TransactionTemplate transactions,
            InstallationReplayAudit audit,
            DataSource dataSource) {
        this.repository = repository;
        this.consumer = consumer;
        this.transactions = transactions;
        this.audit = audit;
        this.dataSource = dataSource;
    }

    public ConsumerDeadLetterDTO replayWorkspace(Long workspaceId, Long id, Long actorId) {
        return replay(
                workspaceId,
                actorId,
                repository
                        .findWorkspace(workspaceId, id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @WorkspaceAgnostic("Instance replay selects only unassigned installation rows, never workspace data")
    public ConsumerDeadLetterDTO replayInstallation(Long id, Long actorId) {
        return replay(
                null,
                actorId,
                repository.findInstallation(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private ConsumerDeadLetterDTO replay(@Nullable Long workspaceId, Long actorId, ConsumerDeadLetter row) {
        if (row.getReplayedAt() != null) return ConsumerDeadLetterDTO.from(row);
        MessageInfo stored;
        try {
            stored = consumer.readRetainedMessage(row.getStreamName(), row.getStreamSequence());
        } catch (JetStreamApiException failure) {
            HttpStatus status = failure.getErrorCode() == 404 ? HttpStatus.GONE : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(
                    status, "The retained event is unavailable. Check the broker and its retention.", failure);
        } catch (IOException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "We could not reach the event broker. Try again.", failure);
        }
        var storedTime = stored.getTime();
        if (storedTime == null
                || !row.getSubject().equals(stored.getSubject())
                || !row.getReceivedAt().equals(storedTime.toInstant().truncatedTo(ChronoUnit.MICROS))) {
            throw new ResponseStatusException(HttpStatus.GONE, "The original event is no longer retained.");
        }
        // Fetch from NATS before opening a database transaction or holding a row lock.
        try {
            String consumerName = consumer.fencedConsumerName(workspaceId, row.getStreamName());
            return oneReplayAtATime(
                    consumerName,
                    () -> consumer.exclusive(
                            workspaceId,
                            row.getStreamName(),
                            () -> apply(workspaceId, actorId, row, stored, storedTime.toInstant())));
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (ConsumerFenceException failure) {
            log.warn(
                    "Replay fence failed: deadLetterId={}, error.type={}",
                    row.getId(),
                    failure.getClass().getName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Another event from this source is still being processed or retried. Try again in a minute.",
                    failure);
        } catch (RuntimeException failure) {
            log.warn(
                    "Replay failed: deadLetterId={}, error.type={}",
                    row.getId(),
                    failure.getClass().getName());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "We could not complete this replay. Check the connection and try again.",
                    failure);
        }
    }

    private <T> T oneReplayAtATime(String consumerName, Supplier<T> replay) {
        // An autocommit connection with no transaction: the replay's own transactions take other connections.
        try (Connection connection = dataSource.getConnection()) {
            if (!holds(connection, LOCK, consumerName)) {
                throw new ConsumerFenceException("Another replay of the consumer runs", null);
            }
            try {
                return replay.get();
            } finally {
                release(connection, consumerName);
            }
        } catch (SQLException failure) {
            throw new ConsumerFenceException("The replay lock is unavailable", failure);
        }
    }

    /** A pooled connection that may still hold the lock must not return to the pool, so a failed unlock aborts it. */
    private static void release(Connection connection, String consumerName) {
        try {
            if (holds(connection, UNLOCK, consumerName)) return;
        } catch (SQLException failure) {
            log.warn("Replay unlock failed: error.type={}", failure.getClass().getName());
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException failure) {
            log.warn(
                    "Replay connection abort failed: error.type={}",
                    failure.getClass().getName());
        }
    }

    private static boolean holds(Connection connection, String sql, String consumerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, consumerName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private ConsumerDeadLetterDTO apply(
            @Nullable Long workspaceId, Long actorId, ConsumerDeadLetter row, MessageInfo stored, Instant storedTime) {
        if (row.getReplayedAt() != null) return ConsumerDeadLetterDTO.from(row);
        if (!consumer.isReplayAdmitted(workspaceId, row.getStreamName(), row.getSubject())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This event is no longer in the workspace's subscription.");
        }
        var message = new ReplayMessage(row, stored, storedTime);
        try {
            if (consumer.dispatch(workspaceId, message) == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The event route is no longer available.");
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "We could not replay this event. Check the connection and try again.",
                    failure);
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            ConsumerDeadLetter locked = (workspaceId == null
                            ? repository.lockInstallation(row.getId())
                            : repository.lockWorkspace(workspaceId, row.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (locked.getReplayedAt() != null) return ConsumerDeadLetterDTO.from(locked);
            if (workspaceId == null && !audit.record(actorId, locked.getId())) {
                throw new IllegalStateException("The replay audit could not be recorded");
            }
            locked.setReplayedAt(Instant.now());
            locked.setReplayedBy(actorId);
            return ConsumerDeadLetterDTO.from(locked);
        }));
    }

    /** Preserve the broker timestamp used by SCM event identities, without a live acknowledgement channel. */
    private static final class ReplayMessage extends NatsMessage {
        ReplayMessage(ConsumerDeadLetter row, MessageInfo stored, Instant storedTime) {
            super(row.getSubject(), reply(row, storedTime), stored.getHeaders(), stored.getData());
        }

        private static String reply(ConsumerDeadLetter row, Instant at) {
            long nanos = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
            return "$JS.ACK." + row.getStreamName() + "." + row.getConsumerName() + ".1." + row.getStreamSequence()
                    + ".1." + nanos + ".0";
        }

        @Override
        public boolean isJetStream() {
            return true;
        }

        @Override
        public NatsJetStreamMetaData metaData() {
            return new NatsJetStreamMetaData(this);
        }
    }
}
