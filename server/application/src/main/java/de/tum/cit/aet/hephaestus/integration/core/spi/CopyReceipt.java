package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CopyReceipt(
        UUID copyId,
        CopyDisposition disposition,
        @Nullable String externalRef,
        @Nullable String externalUrl,
        @Nullable Boolean writeMayHaveStarted,
        @Nullable CopyForm actualKind,
        @Nullable String threadRef) {
    public CopyReceipt(
            UUID copyId,
            CopyDisposition disposition,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            @Nullable Boolean writeMayHaveStarted,
            @Nullable CopyForm actualKind) {
        this(copyId, disposition, externalRef, externalUrl, writeMayHaveStarted, actualKind, null);
    }

    public boolean acknowledged() {
        return disposition != CopyDisposition.FAILED && externalRef != null && !externalRef.isBlank();
    }

    public boolean unconfirmed() {
        return !acknowledged() && !Boolean.FALSE.equals(writeMayHaveStarted);
    }

    public static CopyReceipt notSent(UUID copyId) {
        return new CopyReceipt(copyId, CopyDisposition.FAILED, null, null, false, null);
    }

    public static CopyReceipt attempted(UUID copyId) {
        return new CopyReceipt(copyId, CopyDisposition.FAILED, null, null, true, null);
    }
}
