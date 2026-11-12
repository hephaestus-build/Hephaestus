package de.tum.cit.aet.hephaestus.integration.core.fabric;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import java.io.IOException;

/** Owned source directories for tests that mock capture admission. */
public final class EvidenceSpoolFixtures {
    private EvidenceSpoolFixtures() {}

    public static EvidenceSpool.Copy directory(EvidenceSpool spool, long workspaceId) throws IOException {
        var scope = spool.capture(workspaceId, () -> mock(PersonDataCopyFence.Lease.class));
        try (scope) {
            return spool.directory(workspaceId);
        }
    }
}
