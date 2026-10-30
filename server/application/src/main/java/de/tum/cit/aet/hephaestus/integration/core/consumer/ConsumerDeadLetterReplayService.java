package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.InstallationReplayAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.MessageInfo;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.impl.NatsMessage;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
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

    public ConsumerDeadLetterReplayService(
            ConsumerDeadLetterRepository repository,
            IntegrationNatsConsumer consumer,
            TransactionTemplate transactions,
            InstallationReplayAudit audit) {
        this.repository = repository;
        this.consumer = consumer;
        this.transactions = transactions;
        this.audit = audit;
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
            return consumer.serialDispatch(
                    workspaceId,
                    row.getStreamName(),
                    () -> Objects.requireNonNull(transactions.execute(status -> {
                        ConsumerDeadLetter locked = (workspaceId == null
                                        ? repository.lockInstallation(row.getId())
                                        : repository.lockWorkspace(workspaceId, row.getId()))
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                        return apply(workspaceId, actorId, locked, stored, storedTime.toInstant());
                    })));
        } catch (ResponseStatusException failure) {
            throw failure;
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
        if (workspaceId == null && !audit.record(actorId, row.getId())) {
            throw new IllegalStateException("The replay audit could not be recorded");
        }
        row.setReplayedAt(Instant.now());
        row.setReplayedBy(actorId);
        return ConsumerDeadLetterDTO.from(row);
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
