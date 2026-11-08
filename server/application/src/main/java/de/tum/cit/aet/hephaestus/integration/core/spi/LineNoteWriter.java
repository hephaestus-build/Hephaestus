package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface LineNoteWriter {
    IntegrationKind kind();

    CopyWriteResult postPackage(FeedbackTarget target, OnWorkPackage publication, CopyWriteFence fence);

    @Nullable
    List<CopyReceipt> findPosted(FeedbackTarget target, OnWorkPackage publication);

    List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> copies);

    /** Writes each retracted note's words back into the same provider note. Never posts another note. */
    List<CopyUpdateReceipt> restore(FeedbackTarget target, List<RetractedNote> notes);

    /** Changes the words of an owned note in place. The previous delivery key must still be present. */
    List<CopyUpdateReceipt> editOwn(FeedbackTarget target, List<OwnedEdit> notes);

    record OwnedEdit(CopyReceipt copy, String previousWriteKey, LineNote note) {}

    record RetractedNote(CopyReceipt copy, LineNote note) {}
}
