package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.io.Serial;

/** A refused snapshot. It carries the code alone: a key or a name from the document is the submitter's content. */
public class SnapshotRefusedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final SnapshotRefusal refusal;

    public SnapshotRefusedException(SnapshotRefusal refusal) {
        super(refusal.name());
        this.refusal = refusal;
    }

    public SnapshotRefusal refusal() {
        return refusal;
    }
}
