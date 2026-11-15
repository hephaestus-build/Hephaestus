package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationExportAdapter implements AccountConversationExport {
    private final ConversationEntryRepository entries;

    @Override
    public List<Message> messages(long accountId) {
        return entries.findOwnMessages(accountId).stream()
                .map(m -> new Message(
                        m.getWorkspace(),
                        m.getConversationId(),
                        m.getSurface(),
                        m.getAuthor(),
                        m.getText(),
                        m.getWrittenAt(),
                        m.getEditedAt()))
                .toList();
    }
}
