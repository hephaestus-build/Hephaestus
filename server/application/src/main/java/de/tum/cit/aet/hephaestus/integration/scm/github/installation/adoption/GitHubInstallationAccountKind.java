package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The kind of GitHub account that an installation is on. */
public enum GitHubInstallationAccountKind {
    USER,
    ORGANIZATION;

    /** GitHub's {@code target_type}: {@code User} or {@code Organization}. An enterprise installation has neither. */
    static Optional<GitHubInstallationAccountKind> ofTargetType(@Nullable String targetType) {
        return switch (targetType) {
            case "User" -> Optional.of(USER);
            case "Organization" -> Optional.of(ORGANIZATION);
            case null, default -> Optional.empty();
        };
    }
}
