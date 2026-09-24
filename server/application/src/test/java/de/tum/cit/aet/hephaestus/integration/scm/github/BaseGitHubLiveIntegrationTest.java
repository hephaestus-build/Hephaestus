package de.tum.cit.aet.hephaestus.integration.scm.github;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LiveGitHubTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for live GitHub API integration tests. These tests make real
 * network calls
 * to GitHub's API and require valid GitHub App credentials. They are tagged
 * with "live"
 * and will only run when the profile is explicitly activated:
 *
 * <pre>
 *   ./gradlew :application:liveTest
 * </pre>
 *
 * These tests are excluded from every non-live Gradle tier to keep CI
 * fast
 * and prevent accidental API calls. Use them to validate actual GitHub API
 * behavior.
 *
 * Required configuration (in application-live-local.yml or environment
 * variables):
 * - github.app.id: GitHub App ID
 * - github.app.privateKey: GitHub App private key (PEM format)
 * - integration-tests.github.installation-id: GitHub App installation ID
 * - github.meta.auth-token: Personal access token for tests
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles(value = "live", inheritProfiles = true)
@LiveGitHubTest
public abstract class BaseGitHubLiveIntegrationTest extends BaseIntegrationTest {

    private static final DateTimeFormatter SUFFIX_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    @Autowired
    private Environment environment;

    @Autowired
    private ResourceLoader resourceLoader;

    @BeforeEach
    void requireGitHubCredentials() {
        assumeGitHubCredentialsConfigured();
    }

    protected void assumeGitHubCredentialsConfigured() {
        long appId = environment.getProperty("github.app.id", Long.class, 0L);
        var privateKey = environment.getProperty("github.app.privateKey", "");
        var privateKeyLocation = environment.getProperty("github.app.privateKeyLocation", "");
        // Note: auth-token (PAT) is optional - only used as fallback for certain
        // operations
        var installationId = environment.getProperty("integration-tests.github.installation-id", "");

        // Check key material - need to compute this in a way that produces an
        // effectively final result
        boolean hasKeyMaterial;
        if (!privateKey.isBlank()) {
            hasKeyMaterial = true;
        } else if (!privateKeyLocation.isBlank()) {
            var resource = resourceLoader.getResource(privateKeyLocation);
            try (var input = resource.exists() ? resource.getInputStream() : null) {
                hasKeyMaterial = input != null && input.read() != -1;
            } catch (Exception ignored) {
                hasKeyMaterial = false;
            }
        } else {
            hasKeyMaterial = false;
        }

        boolean missingInstallation = installationId.isBlank();
        boolean missingAppId = appId <= 0;
        boolean missingKeyMaterial = !hasKeyMaterial;

        // Note: auth-token is optional - it's only used as fallback for certain
        // operations that the installation token can't perform (e.g., creating repos in
        // some orgs)
        Assumptions.assumeFalse(
                missingAppId || missingKeyMaterial || missingInstallation,
                () -> "GitHub integration credentials missing. Missing: " + (missingAppId ? "appId " : "")
                        + (missingKeyMaterial ? "privateKey " : "")
                        + (missingInstallation ? "installationId " : "")
                        + ". Copy application-live-local.example.yml to application-live-local.yml "
                        + "or provide environment overrides.");
    }

    protected String githubOrganization() {
        return environment.getProperty("integration-tests.github.organization", "");
    }

    protected long githubInstallationId() {
        var raw = environment.getProperty("integration-tests.github.installation-id", "");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("GitHub installation id not configured for integration tests.");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("GitHub installation id is not a valid number: '" + raw + "'", ex);
        }
    }

    protected String sandboxRepository() {
        return environment.getProperty("integration-tests.github.sandbox-repository", "");
    }

    protected String nextEphemeralSlug(String suffix) {
        var prefix = environment.getProperty("integration-tests.ephemeral-prefix", "hephaestus-it");
        var timestamp = SUFFIX_FORMATTER.format(Instant.now());
        return (prefix + "-" + timestamp + (suffix == null || suffix.isBlank() ? "" : "-" + suffix)).toLowerCase();
    }
}
