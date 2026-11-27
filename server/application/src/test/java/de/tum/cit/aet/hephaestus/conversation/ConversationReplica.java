package de.tum.cit.aet.hephaestus.conversation;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** The same ledger database with an independent replica's live state and notification delivery. */
public final class ConversationReplica {
    private final ConversationLedgerNotifications notifications;
    private final ObjectMapper mapper;
    private final ConversationTimeline timeline;

    public ConversationReplica(ApplicationContext beans) {
        this.mapper = beans.getBean(ObjectMapper.class);
        var conversations = beans.getBean(ConversationRepository.class);
        var turns = beans.getBean(ConversationTurnRepository.class);
        var progress = new ConversationTurnProgress(mapper, conversations);
        this.notifications =
                new ConversationLedgerNotifications(turns, progress, beans.getBean(DataSourceProperties.class), mapper);
        this.timeline = new ConversationTimeline(
                conversations,
                beans.getBean(ConversationEntryRepository.class),
                turns,
                beans.getBean(ConversationTurnWaits.class),
                progress,
                beans.getBean(ConversationEntryViews.class));
    }

    public void deliver(ConversationLedgerEvents.LedgerChanged event) {
        notifications.dispatch(
                mapper.writerFor(ConversationLedgerEvents.LedgerChanged.class).writeValueAsString(event));
    }

    public ConversationTimeline timeline() {
        return timeline;
    }
}
