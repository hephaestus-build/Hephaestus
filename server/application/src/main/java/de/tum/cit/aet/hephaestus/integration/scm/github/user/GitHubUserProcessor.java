package de.tum.cit.aet.hephaestus.integration.scm.github.user;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Upserts provider evidence in a short independent transaction, before returning the caller's managed user. */
@Service
public class GitHubUserProcessor {
    private static final Logger log = LoggerFactory.getLogger(GitHubUserProcessor.class);
    private static final RetryPolicy CONFLICTS = RetryPolicy.builder()
            .includes(PessimisticLockingFailureException.class)
            .maxRetries(3)
            .delay(Duration.ofMillis(100))
            .jitter(Duration.ofMillis(50))
            .multiplier(2)
            .maxDelay(Duration.ofSeconds(1))
            .build();

    private final UserRepository userRepository;
    private final TransactionTemplate requiresNewTransaction;
    private final RetryTemplate retries = new RetryTemplate(CONFLICTS);

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public GitHubUserProcessor(UserRepository userRepository, TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.requiresNewTransaction =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A caller that already owns a transaction must retry its whole use case after contention: suspending it would
     * still retain its connection and locks during backoff. Standalone calls retry only after an attempt rolls back.
     */
    public @Nullable User findOrCreate(GitHubUserDTO dto, Long providerId) {
        if (dto == null || dto.getDatabaseId() == null) return null;
        var write = new UserWrite(dto, Objects.requireNonNull(dto.getDatabaseId()), providerId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager().flush();
            requiresNewTransaction.executeWithoutResult(status -> upsert(write));
            return load(write);
        }
        return retries.invoke(() -> Objects.requireNonNull(requiresNewTransaction.execute(status -> {
                    upsert(write);
                    return Optional.ofNullable(load(write));
                })))
                .orElse(null);
    }

    private void upsert(UserWrite write) {
        var dto = write.dto();
        if (!userRepository.tryAcquireLoginLock(dto.login(), write.providerId()))
            throw new CannotAcquireLockException("The provider login is being changed by another transaction");
        // Separate statements see the released login before INSERT; a data-modifying CTE shares one snapshot.
        userRepository.freeLoginConflicts(dto.login(), write.userId(), write.providerId());
        userRepository.upsertUser(
                write.userId(),
                write.providerId(),
                dto.login(),
                dto.name(),
                dto.avatarUrl() != null ? dto.avatarUrl() : "",
                dto.htmlUrl() != null ? dto.htmlUrl() : "",
                dto.getEffectiveType().name(),
                dto.email(),
                dto.createdAt(),
                dto.updatedAt());
    }

    private @Nullable User load(UserWrite write) {
        User user = userRepository
                .findByNativeIdAndProviderId(write.userId(), write.providerId())
                .orElse(null);
        if (user != null) entityManager().refresh(user);
        else log.warn("User not found after upsert: nativeId={}, providerId={}", write.userId(), write.providerId());
        return user;
    }

    public @Nullable User ensureExists(GitHubUserDTO dto, Long providerId) {
        return findOrCreate(dto, providerId);
    }

    private EntityManager entityManager() {
        return Objects.requireNonNull(entityManager);
    }

    private record UserWrite(GitHubUserDTO dto, long userId, long providerId) {}
}
