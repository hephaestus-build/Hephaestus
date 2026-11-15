package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Bot activity must not create accounts.
@Service
@RequiredArgsConstructor
public class ActivityPersonWriteAdmission {
    private final ActivityScmFacts scm;
    private final PersonDataWriteFence fence;

    public boolean hold(User actor) {
        return fence.holdForWrite(List.of(identity(actor)));
    }

    public boolean holdActor(long actorId) {
        return scm.user(actorId).map(this::hold).orElse(false);
    }

    public List<Long> holdActors(List<Long> actorIds) {
        if (actorIds.isEmpty()) return List.of();
        var actors = scm.users(actorIds);
        var identities =
                actors.stream().map(ActivityPersonWriteAdmission::identity).toList();
        if (identities.isEmpty()) return List.of();
        // Acquire the whole ordered lock set before filtering. Re-entry cannot invert the lock order.
        if (fence.holdForWrite(identities))
            return actors.stream().map(User::getId).toList();
        return actors.stream().filter(this::hold).map(User::getId).toList();
    }

    private static PersonIdentity identity(User actor) {
        return new PersonIdentity(
                Objects.requireNonNull(actor.getProvider().getId()),
                actor.getNativeId().toString(),
                null);
    }
}
