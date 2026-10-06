package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommitDetailsCheckpointIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private CommitRepository commits;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldDistinguishCapturedEmptyCommitsFromUnenrichedStubsWithinEachRepository() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Repository first = repository(provider, 71001L, "first");
        Repository second = repository(provider, 71002L, "second");
        String empty = "a".repeat(40);
        String stub = "b".repeat(40);
        upsert(first, empty, "Empty commit", null);
        upsert(first, stub, "Empty commit", null);
        upsert(second, empty, "Empty commit", null);
        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .isEmpty();

        upsert(first, empty, "Empty commit", Instant.now());

        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .containsExactly(empty);
        assertThat(commits.findGitDetailsCapturedShas(second.getId(), List.of(empty)))
                .isEmpty();
    }

    @Test
    void shouldKeepWhatNativeGitCapturedWhenALesserSourceUpsertsTheSameCommit() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Repository repository = repository(provider, 71003L, "guarded");
        String sha = "c".repeat(40);
        Instant captured = Instant.parse("2024-01-15T10:00:00Z");
        Instant authored = Instant.parse("2024-01-14T09:00:00Z");
        commits.upsertCommit(
                sha,
                "Native subject",
                null,
                "https://github.com/checkpoint/guarded/commit/" + sha,
                authored,
                authored,
                3,
                1,
                2,
                captured,
                repository.getId(),
                null,
                null,
                "author@example.com",
                "author@example.com",
                captured);

        upsert(repository, sha, "Webhook subject", null);

        Commit commit =
                commits.findByShaAndRepositoryId(sha, repository.getId()).orElseThrow();
        assertThat(commit.getMessage()).isEqualTo("Native subject");
        assertThat(commit.getAuthoredAt()).isEqualTo(authored);
        assertThat(commit.getCommittedAt()).isEqualTo(authored);
        assertThat(commit.getAdditions()).isEqualTo(3);
        assertThat(commit.getGitDetailsCapturedAt()).isEqualTo(captured);
    }

    @Test
    void shouldReplaceOnlyTheNamedCommitsFileChangesThroughAnUncorrelatedDelete() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Repository first = repository(provider, 71004L, "replaced");
        Repository second = repository(provider, 71005L, "neighbour");
        String sha = "d".repeat(40);
        String other = "e".repeat(40);
        upsert(first, sha, "Target", null);
        upsert(first, other, "Same repository", null);
        upsert(second, sha, "Same SHA", null);
        long target = commitId(first, sha);
        long sameRepository = commitId(first, other);
        long sameSha = commitId(second, sha);
        for (long commit : List.of(target, sameRepository, sameSha)) {
            addFileChange(commit, "README.md");
        }

        List<String> deletes = capturedSql(() -> commits.deleteFileChanges(first.getId(), sha)).stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("delete from commit_file_change"))
                .toList();
        addFileChange(target, "CHANGELOG.md");
        commits.deleteFileChanges(first.getId(), sha);
        addFileChange(target, "CHANGELOG.md");

        assertThat(fileNames(target)).containsExactly("CHANGELOG.md");
        assertThat(fileNames(sameRepository)).containsExactly("README.md");
        assertThat(fileNames(sameSha)).containsExactly("README.md");
        assertThat(deletes).hasSize(1);
        String plan =
                String.join("\n", jdbc.queryForList("EXPLAIN " + deletes.getFirst(), String.class, first.getId(), sha));
        assertThat(plan).contains("InitPlan").doesNotContain("SubPlan");
    }

    private long commitId(Repository repository, String sha) {
        return commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId();
    }

    private void addFileChange(long commitId, String filename) {
        jdbc.update(
                "INSERT INTO commit_file_change (filename, change_type, additions, deletions, changes, commit_id) "
                        + "VALUES (?, 'MODIFIED', 1, 0, 1, ?)",
                filename,
                commitId);
    }

    private List<String> fileNames(long commitId) {
        return jdbc
                .queryForList(
                        "SELECT filename FROM commit_file_change WHERE commit_id = ? ORDER BY id",
                        String.class,
                        commitId)
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private static List<String> capturedSql(Runnable work) {
        var logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level level = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        try {
            work.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(events);
            events.stop();
        }
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Repository repository(IdentityProvider provider, long nativeId, String name) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName(name);
        repository.setNameWithOwner("checkpoint/" + name);
        repository.setHtmlUrl("https://github.com/checkpoint/" + name);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setProvider(provider);
        return repositories.save(repository);
    }

    private void upsert(Repository repository, String sha, String message, @Nullable Instant capturedAt) {
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                message,
                null,
                "https://github.com/checkpoint/commit/" + sha,
                now,
                now,
                0,
                0,
                0,
                now,
                repository.getId(),
                null,
                null,
                "author@example.com",
                "author@example.com",
                capturedAt);
    }
}
