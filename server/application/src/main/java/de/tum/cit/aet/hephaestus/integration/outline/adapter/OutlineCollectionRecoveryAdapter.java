package de.tum.cit.aet.hephaestus.integration.outline.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredCollectionErasures;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erasure recovery erases a restored Outline collection again, as the admin's erase
 * ({@link de.tum.cit.aet.hephaestus.integration.outline.collection.OutlineCollectionAdminService#delete}) did. It
 * reads the repositories directly: the admin service exists only while Outline is turned on, and a restore can hold a
 * collection from a time when it was on.
 */
@Component
class OutlineCollectionRecoveryAdapter implements RestoredCollectionErasures {
    private final OutlineCollectionRepository collections;
    private final OutlineDocumentRepository documents;

    OutlineCollectionRecoveryAdapter(OutlineCollectionRepository collections, OutlineDocumentRepository documents) {
        this.collections = collections;
        this.documents = documents;
    }

    @Override
    @Transactional
    public boolean eraseAgain(long workspaceId, long connectionId, String collectionId) {
        long erased =
                documents.deleteByWorkspaceIdAndConnectionIdAndCollectionId(workspaceId, connectionId, collectionId);
        var registration =
                collections.findByWorkspaceIdAndConnectionIdAndCollectionId(workspaceId, connectionId, collectionId);
        registration.ifPresent(collections::delete);
        return erased > 0 || registration.isPresent();
    }
}
