package de.tum.cit.aet.hephaestus.conversation;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hears this process's ledger notifications in a test. One probe listens on each context's notifications for the life
 * of that context, so a test registers nothing it must remove.
 */
public final class ConversationLedgerProbe implements ConversationLedgerNotifications.Listener {

    private static final Map<ConversationLedgerNotifications, ConversationLedgerProbe> PROBES =
            new ConcurrentHashMap<>();

    private final Set<UUID> heardTurns = ConcurrentHashMap.newKeySet();

    private ConversationLedgerProbe() {}

    public static ConversationLedgerProbe on(ConversationLedgerNotifications notifications) {
        return PROBES.computeIfAbsent(notifications, listening -> {
            ConversationLedgerProbe probe = new ConversationLedgerProbe();
            listening.subscribe(probe);
            return probe;
        });
    }

    @Override
    public void turnChanged(ConversationLedgerEvents.TurnChanged change) {
        heardTurns.add(change.turnId());
    }

    @Override
    public void entryChanged(ConversationLedgerEvents.EntryChanged change) {}

    /** Returns once this process receives ledger notifications, so a change the test makes next is not missed. */
    public void awaitListening(TransactionTemplate transactions, ConversationTurnRepository turns, Duration wait) {
        UUID probe = UUID.randomUUID();
        await().atMost(wait).until(() -> {
            transactions.executeWithoutResult(status -> turns.notifyLedger(
                    ConversationLedgerNotifications.CHANNEL,
                    "{\"type\":\"turn\",\"workspaceId\":-1,\"conversationId\":\"" + UUID.randomUUID()
                            + "\",\"turnId\":\"" + probe
                            + "\",\"status\":\"QUEUED\",\"surface\":\"APP\",\"attempt\":0}"));
            return heardTurns.remove(probe);
        });
    }
}
