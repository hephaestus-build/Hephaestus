package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateValue;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PullRequestItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return ScmItemEvents.PULL_REQUEST;
    }

    @Override
    public String displayName() {
        return "Pull or merge request";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of(
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_OPENED,
                        "Opened",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        true,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_READY,
                        "Marked ready for review",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        true,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_SYNCHRONIZED,
                        "New commits pushed",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        true,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED,
                        "Checks finished",
                        Set.of(ScmEventSources.GITHUB_CHECK_SUITE, ScmEventSources.GITHUB_STATUS),
                        false,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_EDITED,
                        "Title or description edited",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        false,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_LINKED_ISSUE_UPDATED,
                        "Linked issue outcome updated",
                        Set.of(
                                ScmEventSources.GITHUB_ISSUES,
                                ScmEventSources.GITLAB_ISSUE,
                                ScmEventSources.GITHUB_PULL_REQUEST,
                                ScmEventSources.GITLAB_MERGE_REQUEST),
                        false,
                        false),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_MERGED,
                        "Merged",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        false,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.PULL_REQUEST_CLOSED,
                        "Closed without merging",
                        Set.of(ScmEventSources.GITHUB_PULL_REQUEST, ScmEventSources.GITLAB_MERGE_REQUEST),
                        false,
                        true));
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of(AboutRole.AUTHOR, AboutRole.ASSIGNEE, AboutRole.MERGER);
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE);
    }

    @Override
    public boolean reviewable() {
        return true;
    }

    @Override
    public Set<ItemKind> memberKinds() {
        return Set.of(ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    @Override
    public String recordSchemaResource() {
        return "contracts/items/scm/pull_request.schema.json";
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                        "Repository evidence does not establish behavior in a deployed runtime.")),
                List.of(
                        new ReviewStateDimension(
                                "draftStatus",
                                "Draft status",
                                List.of(
                                        new ReviewStateValue("DRAFT", "Draft"),
                                        new ReviewStateValue("NOT_DRAFT", "Not draft")),
                                Set.of("NOT_DRAFT")),
                        new ReviewStateDimension(
                                "state",
                                "State",
                                List.of(
                                        new ReviewStateValue("OPEN", "Open"),
                                        new ReviewStateValue("CLOSED", "Closed"),
                                        new ReviewStateValue("MERGED", "Merged")),
                                Set.of("OPEN", "CLOSED", "MERGED"))),
                Set.of("CHANGED_PATH", "DIFF_CONTAINS", "HAS_ITEMS"),
                Set.of("scm.review-threads", "scm.inline-review-comments", "scm.general-review-comments"),
                Set.of(ScmItemEvents.FILE));
    }
}
