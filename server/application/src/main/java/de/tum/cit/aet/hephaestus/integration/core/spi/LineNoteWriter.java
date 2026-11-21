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

    /**
     * The provider account this writer acts as, by its provider id, such as the bot of a GitHub App. A thread event with
     * this actor can be Hephaestus's own. Null when the provider cannot say now.
     */
    @Nullable
    String actor(FeedbackTarget target);

    /**
     * Resolves or reopens the thread of one owned note. The thread must still start with that note, and a resolve needs
     * the owned note to be the newest one: a person's later reply refuses it. A thread already in the asked state is
     * {@link ThreadChange.Outcome#DONE}, so a repeated attempt is safe.
     */
    ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved);

    record OwnedEdit(CopyReceipt copy, String previousWriteKey, LineNote note) {}

    record RetractedNote(CopyReceipt copy, LineNote note) {}
}
