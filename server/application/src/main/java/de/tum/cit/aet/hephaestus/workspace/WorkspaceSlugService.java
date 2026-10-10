package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.exception.InvalidWorkspaceSlugException;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceSlugConflictException;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Allocates available workspace names and records permanent rename history. */
@Service
public class WorkspaceSlugService {

    private static final int SLUG_MIN_LENGTH = WorkspaceSlugValidator.MIN_LENGTH;
    private static final int SLUG_MAX_LENGTH = WorkspaceSlugValidator.MAX_LENGTH;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;
    private final WorkspaceSlugReservationRepository reservations;

    public WorkspaceSlugService(
            WorkspaceRepository workspaceRepository,
            WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository,
            WorkspaceSlugReservationRepository reservations) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceSlugHistoryRepository = workspaceSlugHistoryRepository;
        this.reservations = reservations;
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
        if (!WorkspaceSlugValidator.isAssignable(slug)) {
            throw new InvalidWorkspaceSlugException(slug);
        }
    }

    /**
     * Check if a slug is available (valid, not in use, and never previously used).
     *
     * @param slug the slug to check
     * @return true if the slug is available
     */
    public boolean isAvailable(String slug) {
        return WorkspaceSlugValidator.isAssignable(slug)
                && !workspaceRepository.existsByWorkspaceSlug(slug)
                && !reservations.existsById(slug)
                && !workspaceSlugHistoryRepository.existsByOldSlug(slug);
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
        String normalized = Objects.requireNonNull(normalize(desiredSlug)).replaceAll("[^a-z0-9-]", "-");
        normalized = Objects.requireNonNull(normalize(normalized));
        if (normalized.isEmpty()) {
            normalized = "workspace";
        }
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
        workspaceSlugHistoryRepository.save(historyEntry);
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
            String hex = HexFormat.of().formatHex(hashBytes);
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
