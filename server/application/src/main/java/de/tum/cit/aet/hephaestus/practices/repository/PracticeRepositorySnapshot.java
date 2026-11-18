package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;

/** What the configuration audit keeps of the practice repository setting. */
record PracticeRepositorySnapshot(long repositoryItemId, String directory) implements ConfigAuditSnapshot {
    static PracticeRepositorySnapshot of(PracticeRepositorySetting setting) {
        return new PracticeRepositorySnapshot(setting.getRepositoryItemId(), setting.getDirectory());
    }
}
