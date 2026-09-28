package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import org.jspecify.annotations.Nullable;

/**
 * The GitHub webhook events Hephaestus consumes, each named by its X-GitHub-Event header value.
 * <p>
 * Every value has a registered message handler ({@code WebhookFixtureHandlerResolutionTest}), and the GitHub App
 * guide's manifest subscribes to every value GitHub does not deliver unasked
 * ({@code scripts/github-app-manifest.test.ts}), so adding a value means adding its handler and its subscription.
 *
 * @see <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads">
 *      GitHub Webhook Events Reference</a>
 */
public enum GitHubEventType {
    // Repository events
    REPOSITORY("repository"),
    ISSUES("issues"),
    ISSUE_COMMENT("issue_comment"),
    PULL_REQUEST("pull_request"),
    PULL_REQUEST_REVIEW("pull_request_review"),
    PULL_REQUEST_REVIEW_COMMENT("pull_request_review_comment"),
    PULL_REQUEST_REVIEW_THREAD("pull_request_review_thread"),
    LABEL("label"),
    MILESTONE("milestone"),
    MEMBER("member"),
    PUSH("push"),

    // Check events: what the checks say about a head, per suite and per commit status
    CHECK_SUITE("check_suite"),
    STATUS("status"),

    // Discussion events
    DISCUSSION("discussion"),
    DISCUSSION_COMMENT("discussion_comment"),

    // Issue hierarchy events
    SUB_ISSUES("sub_issues"),
    ISSUE_DEPENDENCIES("issue_dependencies"),

    // Organization events
    ORGANIZATION("organization"),
    TEAM("team"),
    MEMBERSHIP("membership"),

    // GitHub Projects V2 events
    PROJECTS_V2("projects_v2"),
    PROJECTS_V2_ITEM("projects_v2_item"),
    PROJECTS_V2_STATUS_UPDATE("projects_v2_status_update"),

    // Installation events
    INSTALLATION("installation"),
    INSTALLATION_REPOSITORIES("installation_repositories"),
    INSTALLATION_TARGET("installation_target");

    private final String value;

    GitHubEventType(String value) {
        this.value = value;
    }

    /**
     * Returns the event key string used in NATS subjects.
     */
    public String getValue() {
        return value;
    }

    /**
     * Parses a string event type to the enum value.
     *
     * @param eventType the event type string
     * @return the matching enum value, or null if not found
     */
    public static @Nullable GitHubEventType fromString(@Nullable String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        for (GitHubEventType type : values()) {
            if (type.value.equalsIgnoreCase(eventType)) {
                return type;
            }
        }
        return null;
    }
}
