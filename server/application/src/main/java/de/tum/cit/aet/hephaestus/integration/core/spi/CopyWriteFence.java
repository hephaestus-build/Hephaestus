package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface CopyWriteFence {
    boolean beforeCreate(List<UUID> attempting, List<CopyReceipt> completed);

    /**
     * The check just before the one notifying write of a staged package. A send holds its lease's write margin here,
     * so the request ends before another attempt can read the package back: readback never decides while the
     * request may still land.
     */
    default boolean beforeNotify() {
        return beforeCreate(List.of(), List.of());
    }
}
