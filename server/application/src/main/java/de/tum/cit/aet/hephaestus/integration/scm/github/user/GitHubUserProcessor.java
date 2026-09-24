package de.tum.cit.aet.hephaestus.integration.scm.github.user;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processor for GitHub users.
 * <p>
 * This service handles the conversion of GitHubUserDTO to User entities
 * and persists them. It provides a single processing path for all user-related
 * operations from webhooks and sync services.
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via native SQL upsert (INSERT ON CONFLICT DO UPDATE)</li>
 * <li>Works exclusively with DTOs for complete field coverage</li>
 * <li>All operations use default REQUIRED propagation — joins the caller's
 *     transaction if one exists, or creates a new one when called from
 *     non-transactional contexts (e.g., webhook message handlers)</li>
 * </ul>
 *
 * <b>Concurrency Strategy:</b>
 * <p>
 * User upserts use three separate SQL statements within an isolated REQUIRES_NEW
 * transaction to prevent deadlocks from propagating to the caller:
 * <ol>
 *   <li>Try to acquire a transaction-scoped advisory lock on the login using
 *       {@code pg_try_advisory_xact_lock} (non-blocking to prevent deadlocks)</li>
 *   <li>Rename any other user holding the target login (to {@code RENAMED_<id>})</li>
 *   <li>Insert or update the user via {@code INSERT ... ON CONFLICT (id) DO UPDATE}</li>
 * </ol>
 * These are separate statements (not a CTE) because PostgreSQL data-modifying
 * CTEs all operate on the same snapshot — the INSERT would not see the UPDATE's
 * effect and would still hit the unique constraint. Separate statements each see
 * the results of the previous statement within the same transaction.
 * <p>
 * <b>Deadlock Handling:</b> The upsert runs in a REQUIRES_NEW transaction so that
 * PostgreSQL deadlocks (which mark the transaction for rollback) are isolated from
 * the caller's transaction. When a deadlock is detected, the inner transaction rolls
 * back and is retried with exponential backoff. This handles the case where concurrent
 * virtual threads upsert different users whose primary key index entries fall on the
 * same or adjacent B-tree pages, causing PostgreSQL-level deadlocks independent of
 * the advisory lock strategy.
 */
@Service
@Transactional
public class GitHubUserProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubUserProcessor.class);

    /** Maximum number of attempts to acquire the advisory lock before falling back. */
    private static final int MAX_LOCK_ATTEMPTS = 5;

    /** Base delay between lock acquisition attempts (with jitter). */
    private static final long LOCK_RETRY_BASE_MS = 50;

    /** Maximum number of retry attempts for deadlock recovery. */
    private static final int MAX_DEADLOCK_RETRIES = 3;

    /** Base delay between deadlock retries (with exponential backoff and jitter). */
    private static final long DEADLOCK_RETRY_BASE_MS = 100;

    private final UserRepository userRepository;
    private final TransactionTemplate requiresNewTransaction;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public GitHubUserProcessor(UserRepository userRepository, TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        // Create a REQUIRES_NEW template so the upsert runs in its own transaction.
        // This isolates deadlock rollbacks from the caller's transaction.
        this.requiresNewTransaction =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Find an existing user or create a new one from the DTO.
     * <p>
     * This method is idempotent — calling it multiple times with the same DTO
     * will return the same user entity. Uses default REQUIRED propagation:
     * joins the caller's transaction if one exists, or creates a new one
     * (e.g., when called from non-transactional message handlers).
     * <p>
     * The actual upsert (advisory lock + rename conflicts + INSERT ON CONFLICT)
     * runs in a REQUIRES_NEW transaction to isolate deadlock rollbacks. If
     * PostgreSQL detects a deadlock, only the inner transaction rolls back and
     * the operation is retried with exponential backoff.
     *
     * @param dto the GitHub user DTO
     * @return the User entity (managed in caller's persistence context),
     *     or null if dto is null or has no ID
     */
    @Nullable
    public User findOrCreate(GitHubUserDTO dto, Long providerId) {
        if (dto == null) {
            return null;
        }
        Long userId = dto.getDatabaseId();
        if (userId == null) {
            log.debug("Skipped user processing: reason=missingDatabaseId, userLogin={}", dto.login());
            return null;
        }

        String login = dto.login();
        String name = dto.name();
        String avatarUrl = dto.avatarUrl() != null ? dto.avatarUrl() : "";
        String htmlUrl = dto.htmlUrl() != null ? dto.htmlUrl() : "";
        User.Type userType = dto.getEffectiveType();

        // Flush any pending Hibernate changes before the native SQL to ensure
        // consistency between Hibernate's cache and the database.
        entityManager().flush();

        // Execute the upsert in a REQUIRES_NEW transaction with deadlock retry.
        // This isolates the upsert from the caller's transaction so that if
        // PostgreSQL detects a deadlock and rolls back, only the inner transaction
        // is affected and we can retry without corrupting the caller's state.
        executeUpsertWithDeadlockRetry(userId, providerId, login, name, avatarUrl, htmlUrl, userType, dto);

        // Load the entity by nativeId + providerId. Since the upsert ran in a
        // REQUIRES_NEW transaction that has already committed, the JPQL query
        // goes to DB and returns a managed entity in the caller's persistence context.
        User result =
                userRepository.findByNativeIdAndProviderId(userId, providerId).orElse(null);
        if (result != null) {
            // Refresh to ensure we have the latest data (handles L1 cache staleness
            // if the entity was previously loaded in this transaction).
            entityManager().refresh(result);
        } else {
            // Should not happen after a successful upsert, but handle gracefully
            log.warn("User not found after upsert: nativeId={}, providerId={}, login={}", userId, providerId, login);
        }
        return result;
    }

    /**
     * Executes the upsert operation in a REQUIRES_NEW transaction with deadlock retry.
     * <p>
     * On deadlock (PostgreSQL error 40P01), the inner transaction is rolled back by
     * Spring and we retry with exponential backoff and jitter. The caller's transaction
     * is not affected because we use REQUIRES_NEW propagation.
     */
    // Spring's SQL error-code translator still emits this legacy subtype for configured deadlock codes.
    @SuppressWarnings("deprecation")
    private void executeUpsertWithDeadlockRetry(
            Long userId,
            Long providerId,
            String login,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String htmlUrl,
            User.Type userType,
            GitHubUserDTO dto) {
        for (int attempt = 0; attempt <= MAX_DEADLOCK_RETRIES; attempt++) {
            try {
                requiresNewTransaction.executeWithoutResult(status -> {
                    // Step 1: Try to acquire advisory lock (non-blocking to prevent deadlocks).
                    boolean lockAcquired = tryAcquireWithRetry(login, providerId);

                    if (lockAcquired) {
                        // Step 2: Rename any other user that holds this login (different nativeId).
                        userRepository.freeLoginConflicts(login, userId, providerId);
                    } else {
                        log.debug(
                                "Could not acquire advisory lock after {} attempts, proceeding with upsert: login={}",
                                MAX_LOCK_ATTEMPTS,
                                login);
                    }

                    // Step 3: Insert or update the user.
                    userRepository.upsertUser(
                            userId,
                            providerId,
                            login,
                            name,
                            avatarUrl,
                            htmlUrl,
                            userType.name(),
                            dto.email(),
                            dto.createdAt(),
                            dto.updatedAt());
                });
                // Success — exit the retry loop
                return;
            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                if (attempt < MAX_DEADLOCK_RETRIES) {
                    // Exponential backoff with jitter: base * 2^attempt + random [0, base)
                    long delay =
                            DEADLOCK_RETRY_BASE_MS * (1L << attempt) + (long) (Math.random() * DEADLOCK_RETRY_BASE_MS);
                    log.warn(
                            "Deadlock detected during user upsert, retrying: login={}, attempt={}/{}, delayMs={}",
                            login,
                            attempt + 1,
                            MAX_DEADLOCK_RETRIES,
                            delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        e.addSuppressed(ie);
                        throw e;
                    }
                } else {
                    log.error(
                            "Deadlock persisted after {} retries during user upsert: login={}, userId={}",
                            MAX_DEADLOCK_RETRIES,
                            login,
                            userId);
                    throw e; // Exhausted retries, propagate to caller
                }
            }
        }
    }

    /**
     * Attempts to acquire the advisory lock with retry and jitter.
     * <p>
     * Uses {@code pg_try_advisory_xact_lock} which returns immediately (never blocks).
     * If the lock is held by another transaction, waits briefly with randomized jitter
     * before retrying. This prevents the ABBA deadlock pattern that occurs when
     * blocking locks are acquired in different orders by concurrent transactions.
     *
     * @param login the login to lock on
     * @return true if the lock was acquired, false after all attempts exhausted
     */
    private boolean tryAcquireWithRetry(String login, Long providerId) {
        for (int attempt = 0; attempt < MAX_LOCK_ATTEMPTS; attempt++) {
            if (userRepository.tryAcquireLoginLock(login, providerId)) {
                return true;
            }
            if (attempt < MAX_LOCK_ATTEMPTS - 1) {
                try {
                    // Jittered backoff: base * (attempt+1) + random [0, base)
                    long delay = LOCK_RETRY_BASE_MS * (attempt + 1) + (long) (Math.random() * LOCK_RETRY_BASE_MS);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Ensure a user exists, creating if necessary.
     * <p>
     * Convenience method that delegates to {@link #findOrCreate(GitHubUserDTO)}.
     *
     * @param dto the GitHub user DTO
     * @return the User entity, or null if dto is null or has no ID
     */
    @Nullable
    public User ensureExists(GitHubUserDTO dto, Long providerId) {
        return findOrCreate(dto, providerId);
    }

    private EntityManager entityManager() {
        return Objects.requireNonNull(entityManager);
    }
}
