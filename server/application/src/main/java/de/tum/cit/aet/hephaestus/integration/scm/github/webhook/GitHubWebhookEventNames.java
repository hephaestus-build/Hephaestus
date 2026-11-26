package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import java.util.Locale;
import java.util.Set;

/** Bounded meter labels for GitHub deliveries with no handler. */
final class GitHubWebhookEventNames {

    /** Event names from GitHub's webhook reference that are outside {@link GitHubEventType}. */
    private static final Set<String> OTHER_KNOWN_EVENTS = Set.of(
            "branch_protection_configuration",
            "branch_protection_rule",
            "check_run",
            "code_scanning_alert",
            "commit_comment",
            "create",
            "custom_property",
            "custom_property_values",
            "delete",
            "dependabot_alert",
            "deploy_key",
            "deployment",
            "deployment_protection_rule",
            "deployment_review",
            "deployment_status",
            "discussion",
            "discussion_comment",
            "exemption_request_push_ruleset",
            "fork",
            "github_app_authorization",
            "gollum",
            "issue_relates_to",
            "marketplace_purchase",
            "merge_group",
            "merge_queue_entry",
            "meta",
            "org_block",
            "package",
            "page_build",
            "personal_access_token_request",
            "ping",
            "project",
            "project_card",
            "project_column",
            "projects_v2",
            "projects_v2_item",
            "projects_v2_status_update",
            "public",
            "registry_package",
            "release",
            "repository_advisory",
            "repository_dispatch",
            "repository_import",
            "repository_ruleset",
            "repository_vulnerability_alert",
            "secret_scanning_alert",
            "secret_scanning_alert_location",
            "secret_scanning_scan",
            "security_advisory",
            "security_and_analysis",
            "sponsorship",
            "star",
            "team_add",
            "watch",
            "workflow_dispatch",
            "workflow_job",
            "workflow_run");

    private GitHubWebhookEventNames() {}

    static String metricLabel(String event) {
        String name = event.toLowerCase(Locale.ROOT);
        GitHubEventType handled = GitHubEventType.fromString(name);
        if (handled != null) return handled.getValue();
        return OTHER_KNOWN_EVENTS.contains(name) ? name : "other";
    }
}
