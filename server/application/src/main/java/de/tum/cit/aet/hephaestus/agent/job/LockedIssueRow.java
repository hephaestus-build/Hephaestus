package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The issue a snapshot change is computed from: its row locked against other snapshot changes, then read again.
 *
 * <p>The read comes after the lock because a writer that waited holds an entity loaded before the wait; neither the
 * event that started it nor a second find, which returns that same managed entity, has the fields another writer
 * committed meanwhile. The refresh takes no lock mode of its own, which would turn the row lock into {@code FOR
 * UPDATE}.
 */
@Component
@ConditionalOnServerRole
class LockedIssueRow {

    private final IssueRepository issues;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    LockedIssueRow(IssueRepository issues) {
        this.issues = issues;
    }

    Optional<Issue> lockAndRead(long issueId) {
        if (issues.lockForSnapshotAdvance(issueId).isEmpty()) {
            return Optional.empty();
        }
        EntityManager currentEntityManager = Objects.requireNonNull(entityManager);
        Issue issue = currentEntityManager.find(Issue.class, issueId);
        if (issue == null) {
            return Optional.empty();
        }
        currentEntityManager.refresh(issue);
        return Optional.of(issue);
    }
}
