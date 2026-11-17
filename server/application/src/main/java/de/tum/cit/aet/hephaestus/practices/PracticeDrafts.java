package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way to read draft practices. Every session hides them ({@link Practice#ACTIVE}); the admin paths that create,
 * try, list, activate and delete drafts read through here (ADR 0118 decision 7).
 */
@Component
@RequiredArgsConstructor
public class PracticeDrafts {
    private final PracticeRepository practices;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    /** The practice with this slug, active or draft. */
    @Transactional(readOnly = true)
    public Optional<Practice> find(long workspaceId, String slug) {
        return inSessionWithDrafts(() -> practices.findByWorkspaceIdAndSlug(workspaceId, slug));
    }

    /** Runs {@code work} in the caller's session with draft practices visible, and hides them again after. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T withDrafts(Supplier<T> work) {
        return inSessionWithDrafts(work);
    }

    private <T> T inSessionWithDrafts(Supplier<T> work) {
        var session = Objects.requireNonNull(entityManager).unwrap(Session.class);
        // A nested call finds the filter already off and leaves it off for the outer work.
        boolean hidden = session.getEnabledFilter(Practice.ACTIVE) != null;
        if (hidden) session.disableFilter(Practice.ACTIVE);
        try {
            return work.get();
        } finally {
            if (hidden) session.enableFilter(Practice.ACTIVE);
        }
    }
}
