package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The immutable copies published by one provider review submission. */
public record OnWorkPackage(@Nullable SummaryContent summary, List<LineNote> notes) {
    public OnWorkPackage {
        notes = List.copyOf(notes);
    }

    public List<UUID> copyIds() {
        var ids = new ArrayList<UUID>();
        if (summary != null) ids.add(summary.copyId());
        notes.forEach(note -> ids.add(note.copyId()));
        return List.copyOf(ids);
    }

    public CopyWriteResult notSent() {
        return CopyWriteResult.of(copyIds().stream().map(CopyReceipt::notSent).toList());
    }

    public CopyWriteResult attempted() {
        return CopyWriteResult.of(copyIds().stream().map(CopyReceipt::attempted).toList());
    }
}
