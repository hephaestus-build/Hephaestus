package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.DocumentFeedbackErasure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DocumentFeedbackErasureAdapter implements DocumentFeedbackErasure {
    private final PracticesPrivacyRepository rows;
    private final DerivedResultEraser eraser;

    @Override
    @Transactional
    public int eraseAllDocumentsForWorkspace(long ws) {
        return eraser.erase(ws, rows.documentResultIds(ws));
    }
}
