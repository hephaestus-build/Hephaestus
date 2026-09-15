package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists what native Git captured about one commit, one transaction per commit. A commit that is
 * already captured is left alone; otherwise the row is upserted with its values fitted to the column
 * widths, its file changes replaced, and — when the row did not exist before — a
 * {@link ScmDomainEvent.CommitCreated} published for the listeners that fire after commit. One
 * commit's failure is contained here so a walk over thousands of commits reports it and moves on.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommitDetailsPersister {
    private final CommitRepository commitRepository;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * What only the provider adapter knows about the commits it hands over: the web URL scheme, how an
     * email resolves to a user, and {@code enrichment} — rows only this provider writes, run inside the
     * commit's transaction once its row exists.
     */
    public record Origin(
            Long scopeId,
            DataSource dataSource,
            IdentityProviderType provider,
            UnaryOperator<String> commitUrl,
            Function<String, @Nullable Long> userIdByEmail,
            Enrichment enrichment) {
        public Origin(
                Long scopeId,
                DataSource dataSource,
                IdentityProviderType provider,
                UnaryOperator<String> commitUrl,
                Function<String, @Nullable Long> userIdByEmail) {
            this(scopeId, dataSource, provider, commitUrl, userIdByEmail, (commit, details, author, committer) -> {});
        }
    }

    @FunctionalInterface
    public interface Enrichment {
        void apply(Commit commit, CommitDetails details, @Nullable Long authorId, @Nullable Long committerId);
    }

    public enum Outcome {
        CAPTURED,
        ALREADY_CAPTURED,
        FAILED
    }

    public Outcome persist(CommitDetails details, Repository repository, Origin origin) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> capture(details, repository, origin)));
        } catch (RuntimeException e) {
            log.warn(
                    "Commit capture failed: sha={}, repoId={}, cause={}",
                    details.sha(),
                    repository.getId(),
                    e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    private Outcome capture(CommitDetails details, Repository repository, Origin origin) {
        Long repositoryId = repository.getId();
        Commit existing = commitRepository
                .findByShaAndRepositoryId(details.sha(), repositoryId)
                .orElse(null);
        if (existing != null && existing.getGitDetailsCapturedAt() != null) return Outcome.ALREADY_CAPTURED;

        Long authorId = origin.userIdByEmail().apply(details.authorEmail());
        Long committerId = origin.userIdByEmail().apply(details.committerEmail());
        Instant now = Instant.now();
        commitRepository.upsertCommit(
                details.sha(),
                fit(details.message(), Commit.MESSAGE_LENGTH),
                details.messageBody(),
                fit(origin.commitUrl().apply(details.sha()), Commit.HTML_URL_LENGTH),
                details.authoredAt(),
                details.committedAt(),
                details.additions(),
                details.deletions(),
                details.changedFiles(),
                now,
                repositoryId,
                authorId,
                committerId,
                fit(details.authorEmail(), Commit.EMAIL_LENGTH),
                fit(details.committerEmail(), Commit.EMAIL_LENGTH),
                now);
        commitRepository.deleteFileChanges(repositoryId, details.sha());

        Commit commit = existing != null
                ? existing
                : commitRepository
                        .findByShaAndRepositoryId(details.sha(), repositoryId)
                        .orElseThrow(() -> new IllegalStateException("Commit missing after upsert"));
        commit.getFileChanges().clear();
        for (CommitDetails.FileChange change : details.fileChanges()) {
            CommitFileChange fileChange = new CommitFileChange();
            fileChange.setFilename(fit(change.filename(), CommitFileChange.FILENAME_LENGTH));
            fileChange.setChangeType(change.changeType());
            fileChange.setAdditions(change.additions());
            fileChange.setDeletions(change.deletions());
            fileChange.setChanges(change.changes());
            String previous = change.previousFilename();
            fileChange.setPreviousFilename(previous == null ? null : fit(previous, CommitFileChange.FILENAME_LENGTH));
            commit.addFileChange(fileChange);
        }
        if (!details.fileChanges().isEmpty()) commitRepository.save(commit);

        origin.enrichment().apply(commit, details, authorId, committerId);
        if (existing == null) publishCreated(commit, repository, origin);
        return Outcome.CAPTURED;
    }

    private void publishCreated(Commit commit, Repository repository, Origin origin) {
        EventContext context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                origin.scopeId(),
                RepositoryRef.from(repository),
                origin.dataSource(),
                null,
                UUID.randomUUID().toString(),
                origin.provider());
        eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(ScmEventPayload.CommitData.from(commit), context));
    }

    /** Cuts a value to a column width; the provider adapters use it for the payloads they write themselves. */
    public static String fit(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
