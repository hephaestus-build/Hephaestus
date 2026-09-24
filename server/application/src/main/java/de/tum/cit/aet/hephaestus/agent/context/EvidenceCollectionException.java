package de.tum.cit.aet.hephaestus.agent.context;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public final class EvidenceCollectionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EvidenceCollectionException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
