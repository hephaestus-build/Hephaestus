package de.tum.cit.aet.hephaestus.mentor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Why a queued turn has not started. A turn that a worker may start ({@link MentorTurnRepository#CLAIMABLE}) waits
 * for a free Heph while a host holds another turn. Otherwise the reason is not known: the turn starts at once, waits
 * for an earlier question of its conversation, or no worker runs.
 */
@Service
@RequiredArgsConstructor
public class MentorTurnWaits {

    private final MentorTurnRepository turns;

    /** Why each queued turn among {@code shown} has not started; a turn whose reason is not known has no entry. */
    @Transactional(readOnly = true)
    public Map<UUID, MentorTurn.WaitingFor> waits(long workspaceId, Collection<MentorTurn> shown) {
        List<UUID> queued = shown.stream()
                .filter(turn -> turn.getStatus() == MentorTurn.Status.QUEUED)
                .map(MentorTurn::getId)
                .toList();
        if (queued.isEmpty()) {
            return Map.of();
        }
        Set<UUID> claimable = Set.copyOf(turns.findClaimableIds(workspaceId, queued));
        if (claimable.isEmpty() || !turns.existsHeld()) {
            return Map.of();
        }
        return claimable.stream().collect(Collectors.toMap(Function.identity(), id -> MentorTurn.WaitingFor.FREE_HEPH));
    }
}
