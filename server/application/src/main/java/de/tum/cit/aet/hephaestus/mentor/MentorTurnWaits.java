package de.tum.cit.aet.hephaestus.mentor;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Why a queued turn has not started, by the rule a claim follows ({@link MentorTurnRepository#CLAIMABLE}). A turn that
 * a worker may start waits for a free Heph only while a host holds another turn. Otherwise it starts at once or no
 * worker runs, and the reason is not known.
 */
@Service
@RequiredArgsConstructor
public class MentorTurnWaits {

    private final MentorTurnRepository turns;

    /**
     * Why each queued turn among {@code shown} has not started. A turn that does not wait, or whose reason is not
     * known, has no entry.
     */
    @Transactional(readOnly = true)
    public Map<UUID, MentorTurn.WaitingFor> waits(long workspaceId, Collection<MentorTurn> shown) {
        return reasons(workspaceId, shown);
    }

    /** Why one turn has not started; {@code null} when it does not wait or the reason is not known. */
    @Transactional(readOnly = true)
    public MentorTurn.@Nullable WaitingFor waitingFor(MentorTurn turn) {
        return reasons(turn.getWorkspaceId(), List.of(turn)).get(turn.getId());
    }

    private Map<UUID, MentorTurn.WaitingFor> reasons(long workspaceId, Collection<MentorTurn> shown) {
        List<UUID> queued = shown.stream()
                .filter(turn -> turn.getStatus() == MentorTurn.Status.QUEUED)
                .map(MentorTurn::getId)
                .toList();
        if (queued.isEmpty()) {
            return Map.of();
        }
        Set<UUID> claimable = Set.copyOf(turns.findClaimableIds(workspaceId, queued));
        boolean hephBusy = !claimable.isEmpty() && turns.existsHeld();
        Map<UUID, MentorTurn.WaitingFor> waits = new HashMap<>();
        for (UUID id : queued) {
            if (!claimable.contains(id)) {
                waits.put(id, MentorTurn.WaitingFor.EARLIER_QUESTION);
            } else if (hephBusy) {
                waits.put(id, MentorTurn.WaitingFor.FREE_HEPH);
            }
        }
        return waits;
    }
}
