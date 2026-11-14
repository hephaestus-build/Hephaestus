package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorConversationAccess;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Live admission of proactive work: a queued opt-in is not permission to write after the choice or sources change. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DigestAdmission {
    public static final String REFUSED = "digest_no_longer_eligible";

    private final MentorTurnAccess turns;
    private final MentorConversationAccess conversations;
    private final WeeklyDigestSubscriptions subscriptions;
    private final WeeklyDigestAvailability availability;
    private final WeeklyDigestFacts facts;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Ordinary questions keep their existing admission. Missing or no-longer-eligible digest work is refused. */
    @Transactional(readOnly = true)
    public boolean refuses(long workspaceId, UUID turnId) {
        var turn = turns.find(workspaceId, turnId).orElse(null);
        if (turn == null) return true;
        if (turn.surface() != MentorTurn.Surface.SYSTEM) return false;
        long accountId = conversations.accountId(workspaceId, turn.conversationId());
        var since = subscriptions.enabledSince(accountId);
        if (since.isEmpty() || since.get().isAfter(turn.createdAt()) || !availability.available(workspaceId, accountId))
            return true;
        var stimulus = conversations.stimulus(workspaceId, turnId);
        if (stimulus.kind() != MentorEntry.Kind.DIGEST || stimulus.redacted()) return true;
        var payload = WeeklyDigestPayload.fromJson(stimulus.payload(), mapper);
        if (payload.isEmpty()) return true;
        var window = payload.get().window();
        var now = clock.instant();
        var current = facts.compose(workspaceId, accountId, window);
        return !DigestSchedule.isDue(now, window.zone())
                || !WeeklyDigestWindow.previousWeek(now, window.zone())
                        .weekStart()
                        .equals(window.weekStart())
                || current.isEmpty()
                || !current.get().feedbackIds().containsAll(payload.get().feedbackIds())
                || !current.get().changeKeys().containsAll(payload.get().changeKeys());
    }
}
