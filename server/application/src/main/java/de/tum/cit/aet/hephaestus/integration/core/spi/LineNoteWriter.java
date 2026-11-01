package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface LineNoteWriter {
    IntegrationKind kind();

    CopyWriteResult postPackage(FeedbackTarget target, OnWorkPackage publication, CopyWriteFence fence);

    @Nullable
    List<CopyReceipt> findPosted(FeedbackTarget target, OnWorkPackage publication);

    List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> copies);
}
