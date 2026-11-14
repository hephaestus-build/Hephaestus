package de.tum.cit.aet.hephaestus.integration.scm.github.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubUserContentionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Test
    void shouldRollBackContendedAttemptsBeforeRetryingAndCommitTheProviderUserOnce() {
        long provider = Objects.requireNonNull(
                IdentityProviderFixtures.gitHub(providers).getId());
        long nativeId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        String login = "contention-" + UUID.randomUUID();
        var attempts = new AtomicInteger();
        var revisions = new ArrayList<Long>();
        var repository = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    revisions.add(Objects.requireNonNull(jdbc.queryForObject("select txid_current()", Long.class)));
                    assertThat(users.tryAcquireLoginLock(login, provider)).isTrue();
                    if (attempts.incrementAndGet() <= 2)
                        throw new CannotAcquireLockException("Rollback after taking the actual provider-login lock");
                    return true;
                })
                .when(repository)
                .tryAcquireLoginLock(login, provider);
        var processor = processor(repository);
        var result = processor.findOrCreate(new GitHubUserDTO(nativeId, null, login, null, null, "A", null), provider);
        assertThat(result).isNotNull();
        assertThat(attempts).hasValue(3);
        assertThat(revisions).doesNotHaveDuplicates();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
        assertThat(users.findByNativeIdAndProviderId(nativeId, provider))
                .isPresent()
                .get()
                .extracting(user -> user.getLogin())
                .isEqualTo(login);
        assertThat(jdbc.queryForObject(
                        "select count(*) from \"user\" where native_id = ? and provider_id = ?",
                        Long.class,
                        nativeId,
                        provider))
                .isEqualTo(1L);
    }

    @Test
    void shouldReturnContentionToAnAmbientUseCaseWithoutRetryingWhileItsTransactionIsOpen() {
        long provider = Objects.requireNonNull(
                IdentityProviderFixtures.gitHub(providers).getId());
        String login = "ambient-" + UUID.randomUUID();
        long nativeId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        var attempts = new AtomicInteger();
        var repository = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
                    attempts.incrementAndGet();
                    return false;
                })
                .when(repository)
                .tryAcquireLoginLock(anyString(), anyLong());
        var processor = processor(repository);
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(status -> processor.findOrCreate(
                                new GitHubUserDTO(nativeId, null, login, null, null, "A", null), provider)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(1);
        assertThat(users.findByNativeIdAndProviderId(nativeId, provider)).isEmpty();
    }

    private GitHubUserProcessor processor(UserRepository repository) {
        var processor = new GitHubUserProcessor(repository, new TransactionTemplate(transactions));
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);
        return processor;
    }
}
