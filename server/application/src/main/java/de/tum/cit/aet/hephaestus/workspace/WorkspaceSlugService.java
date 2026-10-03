package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.exception.InvalidWorkspaceSlugException;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceSlugConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for workspace slug allocation, validation, and history management.
 *
 * <p>Handles:
 * <ul>
 *   <li>Slug normalization (lowercase, hyphen-delimited)</li>
 *   <li>Slug validation (pattern matching)</li>
 *   <li>Collision-free slug allocation with hash suffixes</li>
 *   <li>Slug history retention for redirects</li>
 * </ul>
 */
@Service
public class WorkspaceSlugService {

    private static final int SLUG_HISTORY_RETENTION = 5;
    private static final int SLUG_MIN_LENGTH = 3;
    private static final int SLUG_MAX_LENGTH = 51;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;
    private final int redirectTtlDays;

    public WorkspaceSlugService(
            WorkspaceRepository workspaceRepository,
            WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository,
            @Value("${hephaestus.workspace.slug.redirect.ttl-days:30}") int redirectTtlDays) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceSlugHistoryRepository = workspaceSlugHistoryRepository;
        this.redirectTtlDays = redirectTtlDays;
    }

    /**
     * Normalize a slug to lowercase hyphen-delimited format.
     *
     * @param slug the raw slug input
     * @return normalized slug or null if input is null
     */
    public @Nullable String normalize(@Nullable String slug) {
        if (slug == null) {
            return null;
        }
        String normalized = slug.trim().toLowerCase(Locale.ROOT);
        normalized = normalized
                .replace('_', '-')
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return normalized;
    }

    /**
     * Validate a slug against naming rules.
     *
     * @param slug the slug to validate
     * @throws InvalidWorkspaceSlugException if the slug is invalid
     */
    public void validate(String slug) {
        if (slug == null) {
            throw new InvalidWorkspaceSlugException("null");
        }
        if (!slug.matches("^[a-z0-9][a-z0-9-]{2,50}$")) {
            throw new InvalidWorkspaceSlugException(slug);
        }
    }

    /**
     * Check if a slug is available (not in use and no active redirect history).
     *
     * @param slug the slug to check
     * @return true if the slug is available
     */
    public boolean isAvailable(String slug) {
        return !workspaceRepository.existsByWorkspaceSlug(slug) && !hasActiveHistory(slug);
    }

    /**
     * Allocate an available slug, adding suffixes if needed to avoid collisions.
     *
     * @param desiredSlug the preferred slug
     * @param suffixSeed additional entropy for suffix generation (e.g., installation ID)
     * @return an available slug
     * @throws WorkspaceSlugConflictException if no available slug could be found
     */
    public String allocate(String desiredSlug, String suffixSeed) {
        String normalized = Objects.requireNonNull(normalize(desiredSlug));
        if (isAvailable(normalized)) {
            return normalized;
        }

        String seedInput = (suffixSeed == null ? "" : suffixSeed) + "-" + desiredSlug;
        String hash = shortHash(seedInput, 10);
        String suffix = "-" + hash;

        String candidate = buildCandidate(normalized, suffix);
        if (candidate != null) {
            return candidate;
        }

        for (int attempt = 1; attempt <= 50; attempt++) {
            candidate = buildCandidate(normalized, suffix + "-" + attempt);
            if (candidate != null) {
                return candidate;
            }
        }

        throw new WorkspaceSlugConflictException(desiredSlug);
    }

    /**
     * Record a slug rename in history for redirect support.
     *
     * @param workspace the workspace being renamed
     * @param oldSlug the previous slug
     * @param newSlug the new slug
     */
    @Transactional
    public void recordRename(Workspace workspace, String oldSlug, String newSlug) {
        WorkspaceSlugHistory historyEntry = new WorkspaceSlugHistory();
        historyEntry.setWorkspace(workspace);
        historyEntry.setOldSlug(oldSlug);
        historyEntry.setNewSlug(newSlug);
        historyEntry.setChangedAt(Instant.now());
        historyEntry.setRedirectExpiresAt(Instant.now().plusSeconds(redirectTtlDays * 86400L));
        workspaceSlugHistoryRepository.save(historyEntry);

        pruneHistory(workspace);
    }

    /**
     * Find the current slug for a workspace that was previously known by an old slug.
     *
     * @param oldSlug the previous slug
     * @return the current slug, or null if not found or redirect expired
     */
    public @Nullable String resolveRedirect(String oldSlug) {
        return workspaceSlugHistoryRepository
                .findFirstByOldSlugOrderByChangedAtDesc(oldSlug)
                .filter(h -> h.getRedirectExpiresAt() == null
                        || h.getRedirectExpiresAt().isAfter(Instant.now()))
                .map(history -> history.getWorkspace().getWorkspaceSlug())
                .orElse(null);
    }

    // PRIVATE HELPERS

    private boolean hasActiveHistory(String slug) {
        Instant now = Instant.now();
        return (workspaceSlugHistoryRepository.existsByOldSlugAndRedirectExpiresAtIsNull(slug)
                || workspaceSlugHistoryRepository.existsByOldSlugAndRedirectExpiresAtAfter(slug, now));
    }

    private @Nullable String buildCandidate(String baseSlug, String suffix) {
        int maxBaseLen = Math.max(SLUG_MIN_LENGTH, SLUG_MAX_LENGTH - suffix.length());
        String base = baseSlug.length() > maxBaseLen ? baseSlug.substring(0, maxBaseLen) : baseSlug;
        String candidate = Objects.requireNonNull(normalize(base + suffix));
        if (candidate.length() < SLUG_MIN_LENGTH) {
            return null;
        }
        return isAvailable(candidate) ? candidate : null;
    }

    private String shortHash(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = bytesToHex(hashBytes);
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void pruneHistory(Workspace workspace) {
        List<WorkspaceSlugHistory> history =
                workspaceSlugHistoryRepository.findByWorkspaceOrderByChangedAtDesc(workspace);

        if (history.size() <= SLUG_HISTORY_RETENTION) {
            return;
        }

        List<WorkspaceSlugHistory> excess = history.subList(SLUG_HISTORY_RETENTION, history.size());
        workspaceSlugHistoryRepository.deleteAllInBatch(excess);
    }
}
