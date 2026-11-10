package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticesScmMirrorErasureListener {
    private final PracticesPrivacyRepository rows;
    private final DerivedResultEraser eraser;

    @EventListener
    @Transactional
    public void onScmMirrorErased(ScmMirrorErasedEvent event) {
        long ws = event.workspaceId();
        eraser.erase(ws, rows.scmResultIds(ws));
    }
}
