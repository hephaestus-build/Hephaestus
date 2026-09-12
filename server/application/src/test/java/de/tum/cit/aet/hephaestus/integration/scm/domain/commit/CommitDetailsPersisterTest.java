package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
class CommitDetailsPersisterTest {
    private static final String SHA = "a".repeat(40);

    private final CommitRepository commitRepository = mock(CommitRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CommitDetailsPersister persister =
            new CommitDetailsPersister(commitRepository, transactions, eventPublisher);
    private final Repository repository = TestEntities.repository(1L, "owner/repo", "main");
    private final CommitDetailsPersister.Origin origin = new CommitDetailsPersister.Origin(
            100L, DataSource.WEBHOOK, IdentityProviderType.GITHUB, sha -> "https://example.com/" + sha, email -> null);

    CommitDetailsPersisterTest() {
        when(transactions.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));
    }

    private static CommitDetails details(String message, String filename) {
        Instant at = Instant.parse("2024-01-15T10:00:00Z");
        return new CommitDetails(
                SHA,
                message,
                null,
                "Author",
                "author@example.com",
                at,
                "Committer",
                "committer@example.com",
                at,
                1,
                0,
                1,
                List.of(new CommitDetails.FileChange(filename, CommitFileChange.ChangeType.ADDED, 1, 0, 1, filename)),
                List.of());
    }

    @Test
    void shouldFitMessageAndFilenamesToTheirColumnsWhenGitReportsLongerValues() {
        Commit commit = TestEntities.commit(1L, SHA);
        commit.setRepository(repository);
        when(commitRepository.findByShaAndRepositoryId(SHA, 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(commit));

        Outcome outcome = persister.persist(details("m".repeat(2000), "f".repeat(2000)), repository, origin);

        assertThat(outcome).isEqualTo(Outcome.CAPTURED);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(commitRepository)
                .upsertCommit(
                        eq(SHA),
                        message.capture(),
                        any(),
                        eq("https://example.com/" + SHA),
                        any(),
                        any(),
                        eq(1),
                        eq(0),
                        eq(1),
                        any(),
                        eq(1L),
                        any(),
                        any(),
                        eq("author@example.com"),
                        eq("committer@example.com"),
                        any(Instant.class));
        assertThat(message.getValue()).hasSize(Commit.MESSAGE_LENGTH);
        assertThat(commit.getFileChanges()).singleElement().satisfies(change -> {
            assertThat(change.getFilename()).hasSize(CommitFileChange.FILENAME_LENGTH);
            assertThat(change.getPreviousFilename()).hasSize(CommitFileChange.FILENAME_LENGTH);
        });
        verify(eventPublisher).publishEvent(any(ScmDomainEvent.CommitCreated.class));
    }

    @Test
    void shouldReportFailureInsteadOfThrowingWhenTheCommitCannotBePersisted() {
        when(commitRepository.findByShaAndRepositoryId(SHA, 1L)).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("too long"))
                .when(commitRepository)
                .upsertCommit(
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());

        assertThat(persister.persist(details("subject", "file"), repository, origin))
                .isEqualTo(Outcome.FAILED);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void shouldLeaveACapturedCommitAlone() {
        Commit captured = TestEntities.commit(1L, SHA);
        captured.setGitDetailsCapturedAt(Instant.now());
        when(commitRepository.findByShaAndRepositoryId(SHA, 1L)).thenReturn(Optional.of(captured));

        assertThat(persister.persist(details("subject", "file"), repository, origin))
                .isEqualTo(Outcome.ALREADY_CAPTURED);

        verify(commitRepository, never())
                .upsertCommit(
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }
}
