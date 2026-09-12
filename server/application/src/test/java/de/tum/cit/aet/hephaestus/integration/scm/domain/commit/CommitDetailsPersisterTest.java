package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PassThroughTransactionTemplate;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class CommitDetailsPersisterTest extends BaseUnitTest {
    private static final String SHA = "a".repeat(40);

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CommitDetailsPersister persister;
    private final Repository repository = TestEntities.repository(1L, "owner/repo", "main");
    private final CommitDetailsPersister.Origin origin = new CommitDetailsPersister.Origin(
            100L,
            DataSource.WEBHOOK,
            IdentityProviderType.GITHUB,
            sha -> "https://example.com/" + sha,
            email -> email.equals("author@example.com") ? 10L : null);

    @BeforeEach
    void inlineTransactions() {
        persister = new CommitDetailsPersister(commitRepository, new PassThroughTransactionTemplate(), eventPublisher);
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
                        eq(10L),
                        eq(null),
                        eq("author@example.com"),
                        eq("committer@example.com"),
                        any(Instant.class));
        assertThat(message.getValue()).hasSize(Commit.MESSAGE_LENGTH);
        assertThat(commit.getFileChanges()).singleElement().satisfies(change -> {
            assertThat(change.getFilename()).hasSize(CommitFileChange.FILENAME_LENGTH);
            assertThat(change.getPreviousFilename()).hasSize(CommitFileChange.FILENAME_LENGTH);
        });
        verify(commitRepository).save(commit);
    }

    @Test
    void shouldPublishCommitCreatedUnderTheOriginWhenTheRowIsNew() {
        Commit commit = TestEntities.commit(1L, SHA);
        commit.setRepository(repository);
        when(commitRepository.findByShaAndRepositoryId(SHA, 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(commit));

        persister.persist(details("subject", "file"), repository, origin);

        ArgumentCaptor<ScmDomainEvent.CommitCreated> event =
                ArgumentCaptor.forClass(ScmDomainEvent.CommitCreated.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().commit().sha()).isEqualTo(SHA);
        assertThat(event.getValue().context().scopeId()).isEqualTo(100L);
        assertThat(event.getValue().context().source()).isEqualTo(DataSource.WEBHOOK);
        assertThat(event.getValue().context().providerType()).isEqualTo(IdentityProviderType.GITHUB);
    }

    /** A webhook leaves a row without Git details; completing it is a capture, not a second creation. */
    @Test
    void shouldCompleteAnUncapturedRowWithoutPublishingAnotherCreatedEvent() {
        Commit stub = TestEntities.commit(1L, SHA);
        stub.setRepository(repository);
        when(commitRepository.findByShaAndRepositoryId(SHA, 1L)).thenReturn(Optional.of(stub));

        assertThat(persister.persist(details("subject", "file"), repository, origin))
                .isEqualTo(Outcome.CAPTURED);

        verify(commitRepository)
                .upsertCommit(
                        eq(SHA),
                        eq("subject"),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(1L),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(Instant.class));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
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
