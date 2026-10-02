package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeRevisionService {

    private final PracticeRepository practiceRepository;
    private final PracticeRevisionRepository practiceRevisionRepository;

    @Transactional
    public PracticeRevision forReview(Practice practice) {
        Practice locked = practiceRepository
                .findByIdForUpdate(practice.getId())
                .orElseThrow(() -> new EntityNotFoundException("Practice", String.valueOf(practice.getId())));
        PracticeRevision current = locked.getCurrentRevision();
        if (current != null && ReviewRuleFingerprint.isCurrentScheme(current.getReviewRuleFingerprint())) {
            return current;
        }
        return appendLocked(locked);
    }

    /** {@link #forReview(Practice)} for each practice of the workspace with one of these slugs. */
    @Transactional
    public List<PracticeRevision> forReview(Long workspaceId, Collection<String> slugs) {
        return practiceRepository.findByWorkspaceIdAndSlugIn(workspaceId, slugs).stream()
                .map(this::forReview)
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PracticeRevision append(Practice practice) {
        practiceRepository
                .findByIdForUpdate(practice.getId())
                .orElseThrow(() -> new EntityNotFoundException("Practice", String.valueOf(practice.getId())));
        return appendLocked(practice);
    }

    private PracticeRevision appendLocked(Practice practice) {
        int revisionNumber = practiceRevisionRepository
                .findFirstByPracticeIdOrderByRevisionNumberDesc(practice.getId())
                .map(revision -> revision.getRevisionNumber() + 1)
                .orElse(1);
        PracticeRevision saved = practiceRevisionRepository.save(new PracticeRevision(practice, revisionNumber));
        practice.setCurrentRevision(saved);
        practiceRepository.save(practice);
        return saved;
    }

    @Transactional(readOnly = true)
    public @Nullable Integer currentRevisionNumber(Practice practice) {
        return practice.getCurrentRevision() == null
                ? null
                : practice.getCurrentRevision().getRevisionNumber();
    }
}
