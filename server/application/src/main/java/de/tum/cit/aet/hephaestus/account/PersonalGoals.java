package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataErasureAudit;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PersonalGoals {
    private final PersonalGoalRepository goals;
    private final OwnerScope owners;
    private final PersonDataWriteFence writeFence;
    private final PrivacyJournal journal;
    private final PersonDataErasureAudit erasureAudit;

    @Transactional(readOnly = true)
    public PersonalGoalDTO read(PersonAct act) {
        return goals.findById(owners.fromPrincipal(act))
                .map(PersonalGoalDTO::of)
                .orElseGet(() -> new PersonalGoalDTO(0L, null));
    }

    @Transactional
    public PersonalGoalDTO save(PersonAct act, long revision, String words) {
        long owner = admitWrite(act);
        PersonalGoal goal = goals.findById(owner).orElseGet(() -> new PersonalGoal(owner));
        requireRevision(goal, revision);
        goal.save(words);
        return PersonalGoalDTO.of(goals.saveAndFlush(goal));
    }

    @Transactional
    public PersonalGoalDTO forget(PersonAct act, long revision) {
        long owner = admitWrite(act);
        PersonalGoal goal = goals.findById(owner).orElseThrow(PersonalGoals::changed);
        var id = goal.getGoalId();
        if (id == null) throw changed();
        var refs = Map.<String, Object>of("recordId", id);
        if (goal.getWords() == null) {
            // A lost reply or a pending journal write can retry the same exact deletion.
            if (revision != goal.getRevision() - 1
                    || !journal.confirmIfRecorded(id, PrivacyJournalKind.RECORD_DELETED, refs)) throw changed();
        } else {
            requireRevision(goal, revision);
            goal.forget();
            goals.saveAndFlush(goal);
            journal.record(id, PrivacyJournalKind.RECORD_DELETED, refs);
        }
        erasureAudit.requestedByOwner(owner);
        return PersonalGoalDTO.of(goal);
    }

    /** The export publisher already holds the owner and export source fence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @Nullable String wordsForExport(long accountId) {
        return goals.findById(accountId).map(PersonalGoal::getWords).orElse(null);
    }

    private long admitWrite(PersonAct act) {
        long accountId = owners.fromPrincipal(act);
        if (!writeFence.holdForAccountRecordWrite(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account cannot change a goal.");
        }
        goals.lockOwner(accountId);
        return accountId;
    }

    private static void requireRevision(PersonalGoal goal, long revision) {
        if (goal.getRevision() != revision) throw changed();
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Your goal changed. Open it again before you save.");
    }
}
