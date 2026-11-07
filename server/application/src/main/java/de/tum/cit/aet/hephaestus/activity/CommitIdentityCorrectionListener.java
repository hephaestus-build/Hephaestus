package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps derived activity in the same transaction as the provider's correction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class CommitIdentityCorrectionListener {
    private final ActivityLedgerRepair repair;

    @EventListener
    public void onCommitIdentityCorrected(ScmDomainEvent.CommitIdentityCorrected event) {
        repair.correctCommitActors(event.resolutionId());
    }
}
