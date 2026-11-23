package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HistoryCopies {
    private final PersonDataCopyRecorder recorder;

    public HistoryCopy of(ResultHistoryEntry entry) {
        return new HistoryCopy(
                entry.copyAuthorityComplete(), entry.copiedIdentities(), entry.repositoryIds(), entry.sourceJobIds());
    }

    public HistoryCopy of(FeedbackHistoryEntry entry) {
        return new HistoryCopy(
                entry.copyAuthorityComplete(), entry.copiedIdentities(), entry.repositoryIds(), entry.sourceJobIds());
    }

    public boolean record(HistoryCopy copy) {
        if (!copy.complete()) return false;
        copy.identities().forEach(recorder::recordIdentity);
        copy.repositories().forEach(recorder::recordRepository);
        copy.sourceJobs().forEach(recorder::recordCopiedJob);
        return true;
    }

    public void record(HistoryCustody custody) {
        if (!record(new HistoryCopy(
                custody.complete(), custody.copiedIdentities(), custody.repositoryIds(), custody.sourceJobIds())))
            throw new IllegalArgumentException("An observation copy requires custody.");
    }
}
