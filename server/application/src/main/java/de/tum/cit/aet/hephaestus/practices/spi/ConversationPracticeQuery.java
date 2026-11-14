package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import java.util.List;

public interface ConversationPracticeQuery {
    List<ConversationPractice> current(long workspaceId);

    record ConversationPractice(String slug, PracticeDefinition definition) {}
}
